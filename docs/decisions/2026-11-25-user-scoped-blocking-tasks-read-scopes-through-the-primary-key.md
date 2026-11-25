---
title: "The user-scoped blocking-tasks read scopes both of its task rows through the primary key"
date: 2026-11-25
status: accepted
---

# The user-scoped blocking-tasks read scopes both of its task rows through the primary key

## Context

Issue #3195 followed #3176 and #3157. Those issues measured and rewrote three
user-scoped reads — `TaskDao.getBlockingEdgeIdsForUser`,
`TaskDao.observeTypedDependenciesForUser` and `TaskDao.getTagIdsForUser` — and
each of them was left alone first, with the same sentence: "not measured — not
touched". Rewriting a query by analogy would have been a guess, so the next
issue measured it.

The fourth read of the same family is `TaskDao.blockingTasksForUser`: the
still-open prerequisites of one task, as rows rather than as ids, so that the
confirmation a refused close shows can name them. It differs from the other
three in two ways. It selects the whole task row, not an identifier. And it
touches `tasks` twice: once for the task the edge hangs on, and once for the
blocker. The owner filter therefore sits on the blocker side:

```sql
SELECT b.* FROM task_dependencies d
JOIN tasks b ON b.id = d.depends_on_task_id AND b.user_id = :userId
WHERE d.task_id = :taskId AND d.verb = :verb
  AND d.task_id IN (SELECT id FROM tasks WHERE user_id = :userId)
  AND b.completed_at IS NULL AND b.archived_at IS NULL
```

The measurement harness of #3176
(`shared/src/jvmTest/.../performance/DependencyQueryPerformanceHarness.kt`, run
through `:shared:dependencyQueryPerf`) was extended to price this read: the
same datasets from the shared generator of #3090 — nine of them, 10k, 50k and
100k tasks, each with empty, sparse and dense edge distributions, one seed —
the same row-equivalence check on every probed task and over the whole relation
of the profile, and the same scope check: a foreign profile must see nothing, a
task that does not exist must show nothing, and the owner must see only its own
blocking tasks.

What it found: the read is a real bottleneck, and it is the most expensive of
the four. One read costs 9.72 ms on a 10k-task profile, 57.57 ms on a 50k one
and 163.67 ms on a 100k one — on profiles that carry **no edges at all**.

That last part is the difference from the other three reads. Their `AND`
short-circuits: when the task carries no edges, the index lookup finds nothing
and the subquery is never evaluated, so on the datasets without edges the
current form costs 0.02–0.03 ms, the same as the join. This read does not
short-circuit, because its plan drives the read from the owner's task list:

```
SEARCH b USING INDEX index_tasks_user_id (user_id=?)
LIST SUBQUERY 1
SEARCH tasks USING INDEX index_tasks_user_id (user_id=?)
CREATE BLOOM FILTER
SEARCH d USING COVERING INDEX sqlite_autoindex_task_dependencies_1 (task_id=? AND depends_on_task_id=? AND verb=?)
```

`index_tasks_user_id` is the owner's whole task list, and it is walked whether
or not any edge exists.

Two candidates were priced in the same run, in the same process, on the same
restored database. The plain join — the owner filter in the join clause, the
shape the other three reads were rewritten into — costs 1.91 ms on 10k and
33.88 ms on 50k: an owner filter in a join clause is an invitation to drive the
read from the owner's task list, and the plan takes it up
(`SEARCH b USING INDEX index_tasks_user_id (user_id=?)`).

The primary-key-only form — both task rows by primary key, and the owner check
of the blocker moved out of the join clause — costs 0.01–0.10 ms on all nine
datasets.

## Idea

Take both task rows by primary key, and ask the owner question about the
blocker in a way that cannot be answered by the owner's task list. `tasks.id` is
the primary key, so "is there a row with this id and this owner" is exactly
"does this row belong to this owner" — and an `EXISTS` over the primary key
says it that way.

## Decision

`TaskDao.blockingTasksForUser` takes both task rows by primary key, and the
owner of the blocker is checked by an `EXISTS` over the same primary key:

```sql
SELECT b.* FROM task_dependencies d
JOIN tasks t ON t.id = d.task_id AND t.user_id = :userId
JOIN tasks b ON b.id = d.depends_on_task_id
WHERE d.task_id = :taskId AND d.verb = :verb
  AND EXISTS (SELECT 1 FROM tasks ob WHERE ob.id = b.id AND ob.user_id = :userId)
  AND b.completed_at IS NULL AND b.archived_at IS NULL
```

No schema change, no DAO signature change, no index added, no change to the
verb filter or to the "not completed, not archived" filter, no change to write
semantics.

The meaning of the scope is unchanged, clause for clause. The owner of the task
the edge hangs on was `d.task_id IN (SELECT id FROM tasks WHERE user_id =
:userId)`, which is what `JOIN tasks t ON t.id = d.task_id AND t.user_id =
:userId` says. The owner of the blocker was `b.user_id = :userId`, which is
what `EXISTS (SELECT 1 FROM tasks ob WHERE ob.id = b.id AND ob.user_id =
:userId)` says. Both sides of the edge keep the same owner, and a blocker that
is finished, trashed or gone stays out of the way.

The row set is the same: the harness compared the forms on every task it read
and over the whole relation of the profile — as a set, in both directions, and
in number — on every dataset, and they never disagreed; the scope checks
passed on every dataset; `BlockingTasksReadSeamTest` asserts the same against the
real Room database, with a task blocked by an open task, by a finished one, by
a trashed one and by a task of another profile, a task of another profile
blocked by its own, an edge of another verb, and an edge whose owning task does
not exist.

The change is limited to the one query this issue measured.

## Rationale

The evidence is a measurement, not an argument about complexity: the raw SQL
and the DAO call land in the same range (9.39–316.09 ms and 9.72–273.27 ms),
so the cost is in the SQL and not in the Room layer. Both candidate forms were
run as an experiment inside the same harness, in the same process, on the same
restored database, before any production SQL was touched.

The plain join was priced as a separate form rather than dismissed, because
"rewrite it as a join" is the obvious move and it is not enough here: with the
owner filter in the join clause, SQLite may still drive the read from the
owner's task list, and it does. The owner check has to leave the join clause
to make the read independent of the size of the user's task list.

The check was moved into an `EXISTS` rather than into another join clause for
the same reason, and it costs nothing: `tasks.id` is the primary key, so the
blocker row is already in hand, and the `EXISTS` is one lookup on the key
rather than a traversal of the owner's list.

No index was added. The plan of the fast form uses the primary key, which
already exists; the issue forbids adding an index without plan and measurement
evidence, and the plan says the index is there.

The latencies are recorded, not asserted. A clock assertion on a shared machine
is a flaky test wearing a benchmark's clothes. What the harness asserts is the
row equivalence and the scope of the forms, which do not move with the machine
— and a disagreement fails the run: the report is printed and the program exits
with a non-zero status, so a candidate that answers a different question cannot
pass as a successful measurement.

## Consequences

- `TaskDao.blockingTasksForUser` is a primary-key-only read now. A read of one
  task's blocking tasks no longer costs the size of the owner's task list: on
  the measured datasets the read through the DAO drops from 9.72 ms (10k), 57.57
  ms (50k) and 163.67 ms (100k) — on profiles without a single edge — to
  0.17 ms, 0.20 ms and 0.19 ms; over all nine datasets the DAO read costs
  0.12–0.41 ms, against 9.72–273.27 ms before. The tail moves the same way:
  p95 through the DAO goes from 10.74–452.90 ms to 0.18–1.97 ms.
- The price of the read no longer depends on the size of the database, and no
  longer depends on the number of edges: the fast form is 0.01–0.12 ms on every
  one of the nine datasets, sparse and dense alike.
- The scope of the read is unchanged in meaning: the owner sees only its own
  blocking tasks, a foreign profile sees none, and a task that does not exist
  has nothing to show.
- **The whole-relation comparison in the harness spells the scope as an
  `EXISTS` over the primary key, not as `IN (SELECT id FROM tasks WHERE
  user_id = :userId)`.** The two ask the same question, but the list spelling
  answers it once per row of the relation: on a 50k-task profile the comparison
  took minutes instead of a second, and on a 100k-task profile it would not
  have finished. Only the spelling of the *scope* changed; the per-task form,
  whose latency the measurement is about, keeps the list — that list is the
  cost this issue is about.
- The plain join is priced as its own form in the harness (`candidate-join`),
  so the claim that "just add a join" is not enough stays reproducible with the
  documented command.
- The queries of the same shape that are still **not** measured — the checklist
  reads — are left exactly as they are. One measured query at a time.
- The harness is a program in `shared/src/jvmTest`, not a test class: a
  dataset-dependent test would either skip itself or fail for a reason that has
  nothing to do with the claim.

## Links

- Measurement and results: `docs/architecture/dependency-query-performance.md`
- Harness: `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/performance/DependencyQueryPerformanceHarness.kt`
- Gradle task: `:shared:dependencyQueryPerf` in `shared/build.gradle.kts`
- Regression test: `BlockingTasksReadSeamTest` in `shared/src/jvmTest/kotlin/com/singularity/todo/core/database/`
- Issue: https://github.com/gazon1/sing/issues/3195
- Previous measurements: https://github.com/gazon1/sing/issues/3176, https://github.com/gazon1/sing/issues/3157
- Dataset generator: https://github.com/gazon1/sing/issues/3090
