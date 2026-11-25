---
title: "The user-scoped tag read scopes through a join on tasks.id, not through the user's task list"
date: 2026-11-24
status: accepted
---

# The user-scoped tag read scopes through a join on tasks.id, not through the user's task list

## Context

Issue #3176 followed #3157 and #3174. #3174 had measured and rewritten two of the
three user-scoped dependency reads, and had deliberately left the third one —
`TaskDao.getTagIdsForUser` — alone: it was outside that issue's measurement
scope, and rewriting it by analogy would have been a guess.

It asked its scope question in the same old shape:

```sql
AND task_id IN (SELECT id FROM tasks WHERE user_id = :userId)
```

The measurement harness of #3174
(`shared/src/jvmTest/.../performance/DependencyQueryPerformanceHarness.kt`, run
through `:shared:dependencyQueryPerf`) was extended to price this read as well:
the same dataset from the shared generator of #3090, the same three forms
(through the DAO, as the same raw SQL, and as the same question asked as a
primary-key join on `tasks.id`), the same row-equivalence check — on every
probed task and over the whole relation of the profile, as sets, in both
directions and in number — plus a scope check: a foreign profile must see
nothing, and a task that does not exist must show nothing.

What it found: the tag read is a real bottleneck, and its cost is linear in the
number of tasks the user owns. One read of one task's tags costs 10.76 ms as raw
SQL on a 10k-task profile, 80.41 ms on a 50k one and 234.21 ms on an 80k one.
The join form of the same question costs 0.02–0.06 ms everywhere.

The plan says why:

```
SEARCH task_tags USING COVERING INDEX sqlite_autoindex_task_tags_1 (task_id=?)
LIST SUBQUERY 1
SEARCH tasks USING INDEX index_tasks_user_id (user_id=?)
CREATE BLOOM FILTER
```

SQLite materialises the user's entire task-id list on every read, to answer a
question about one cross-ref row it is already holding: whose tag is this? The
cross-ref points at a task, and the task has an owner.

One measurement is worth keeping in the record, because it explains the shape of
the symptom: the `AND` short-circuits. When the task carries no tags, the index
lookup finds nothing and the subquery is never evaluated — on the datasets
without tags the current form costs 0.02–0.03 ms, the same as the join. The
price is paid only by the tasks that actually carry tags, and those are the tasks
the app shows tags for.

## Idea

Scope the read through the task's own row, the way the ordering-edge read and
the two dependency reads already do. `task_tags` has no `user_id` column — that
is why the subquery was there — but the cross-ref row points at a task, and that
task has an owner.

## Decision

`TaskDao.getTagIdsForUser` joins on the task's primary key:

```sql
SELECT tt.tag_id FROM task_tags tt
JOIN tasks t ON t.id = tt.task_id
WHERE tt.task_id = :taskId AND t.user_id = :userId
```

No schema change, no DAO signature change, no index added, no change to tag
ownership, validation or write semantics. The row set is the same: the harness
compared the two forms on every task it read and over the whole relation of the
profile — as a set, in both directions, and in number — on every dataset, and they
never disagreed; the scope checks (foreign profile, unknown task) passed on every
dataset; `JvmDatabaseSeamTest` asserts the same against the real Room database,
with a task that carries two tags, a task of another user, and a cross-ref whose
owning task does not exist.

The comparison over the whole relation is there because a comparison over probed
tasks alone is a sample: a row that only an unprobed task carries would pass
unnoticed. Asking both forms over the relation the profile owns removes that hole
at the cost of four statements per read.

The change is limited to the one query this issue measured.

## Rationale

The evidence is a measurement, not an argument about complexity: one read
through the DAO and the identical SQL as raw SQL land in the same range
(94–263 ms against 80–234 ms on the 50k–90k datasets), so the cost is in the
SQL and not in the Room layer. The candidate form was run as an experiment
inside the same harness before any production SQL was touched, in the same
process, on the same restored database, so the before/after pair is one
measurement and not two machines' impressions.

The rewrite is a rewrite of one statement, not a new abstraction, because the
shape of the cost was local: one statement, one table, one owner column. The
alternatives were worse. Passing the user's task ids in as a parameter keeps
the list-building cost and pushes it up a layer; adding an index on
`tasks(user_id)` does not help, because the index is already used — the plan
shows `SEARCH tasks USING INDEX index_tasks_user_id` — and the cost is the
number of rows it has to walk, not the absence of an index. The issue forbids
adding an index without plan and measurement evidence, and the plan says the
index is there.

The latencies are recorded, not asserted. A clock assertion on a shared machine
is a flaky test wearing a benchmark's clothes. What the harness asserts is the
row equivalence and the scope of the two forms, which do not move with the
machine — and a disagreement fails the run: the report is printed and the
program exits with a non-zero status, so a candidate that answers a different
question cannot pass as a successful measurement.

## Consequences

- `TaskDao.getTagIdsForUser` is a join now. A read of one task's tags no longer
  costs the size of the user's task list: on the measured datasets the read
  through the DAO drops from 12.97 ms (10k), 94.74 ms (50k) and 262.94 ms (90k)
  to 0.29 ms, 0.37 ms and 0.24 ms — the remaining cost is the call around the
  query and does not grow with the database. The tail moves the same way: p95
  through the DAO goes from 18.05 ms (10k) and 375.30 ms (90k) to 1.04 ms and
  0.35 ms. Those p95 numbers come from the ten-task group, where the sample is
  100 observations; in the single-task group the sample is ten observations, so
  its p95 is the maximum of those ten rather than an estimated 95th
  percentile.
- The scope of the read is unchanged in meaning: the owner sees the tags of its
  own task, a foreign profile sees none, the tags of a task that does not exist
  are visible to nobody.
- **A 100k dataset cannot carry tag rows.** The generator's archive budget is
  64 MiB and the 100k task rows alone take 98% of it: a 100k dataset with
  sparse tags serialises to 73 052 440 bytes and a dense one to 80 346 341
  bytes, and the generator refuses both. The largest datasets that do carry tag
  rows are therefore 90k (sparse) and 80k (dense), and the 100k dataset
  measures the no-tags path. This is the same budget that keeps the 100k
  profile itself free of tags, notes and checklist items.
- The queries of the same shape that were **not** measured — the checklist reads
  and the blocking-tasks read — are left exactly as they are. One measured query
  at a time: the checklist read is #3194, the blocking-tasks read is #3195. The
  call overhead the rewrite leaves behind is #3196, and the archive budget that
  caps the largest tag-bearing dataset is #3197.
- The harness is a program in `shared/src/jvmTest`, not a test class: a
  dataset-dependent test would either skip itself or fail for a reason that has
  nothing to do with the claim.

## Links

- Measurement and results: `docs/architecture/dependency-query-performance.md`
- Harness: `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/performance/DependencyQueryPerformanceHarness.kt`
- Gradle task: `:shared:dependencyQueryPerf` in `shared/build.gradle.kts`
- Regression test: `JvmDatabaseSeamTest` in `shared/src/jvmTest/kotlin/com/singularity/todo/core/database/`
- Issue: https://github.com/gazon1/sing/issues/3176
- Previous measurement: https://github.com/gazon1/sing/issues/3157
- Dataset generator: https://github.com/gazon1/sing/issues/3090
- Follow-up measurements: https://github.com/gazon1/sing/issues/3194,
  https://github.com/gazon1/sing/issues/3195,
  https://github.com/gazon1/sing/issues/3196
- Generator budget: https://github.com/gazon1/sing/issues/3197
