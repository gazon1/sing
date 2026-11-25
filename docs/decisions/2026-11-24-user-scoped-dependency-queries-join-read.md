---
title: "The user-scoped dependency reads scope through a join on tasks.id, not through the user's task list"
date: 2026-11-24
status: accepted
---

# The user-scoped dependency reads scope through a join on tasks.id, not through the user's task list

## Context

Issue #3157 asked for the measurement the previous change left out. #3153 had
rewritten one of the three user-scoped dependency reads — the ordering-edge read
the Related Tasks validator walks — and had deliberately left the other two
alone, because they had not been measured and the issue's rule was that a fix
stays inside the confirmed bottleneck:

- `TaskDao.getBlockingEdgeIdsForUser` — the edges that make a task wait,
- `TaskDao.observeTypedDependenciesForUser` — every edge of a task, with its verb.

Both asked their scope question through the same subquery the ordering-edge read
used to ask it through:

```sql
AND task_id IN (SELECT id FROM tasks WHERE user_id = :userId)
```

The measurement harness
(`shared/src/jvmTest/.../performance/DependencyQueryPerformanceHarness.kt`,
run through `:shared:dependencyQueryPerf`) restores a dataset produced by the
shared generator of #3090 through the app's own `BackupImporter` and prices each
read three ways: through the DAO, as the same raw SQL, and as the same question
asked as a primary-key join on `tasks.id`. It measures eight datasets — 10k,
50k and 100k tasks, each with no edges, sparse edges and dense edges — and
records p50/p95 after warmup, the plan SQLite chose, and whether the two SQL
forms return the same rows.

What it found: both queries are real bottlenecks, and their cost is linear in
the number of tasks the user owns. One read of one task's blocking edges costs
8.69 ms on a 10k dataset, 118.69 ms on a 50k one and 234.60 ms on a 100k one,
as raw SQL. The typed read costs 10.40 ms, 108.47 ms and 309.35 ms on the same
three. The join form of the same questions costs 0.02–0.11 ms everywhere.

The plan says why:

```
SEARCH task_dependencies USING COVERING INDEX sqlite_autoindex_task_dependencies_1 (task_id=?)
LIST SUBQUERY 1
SEARCH tasks USING INDEX index_tasks_user_id (user_id=?)
CREATE BLOOM FILTER
```

SQLite materialises the user's entire task-id list on every read, to answer a
question about one row it is already holding: whose edge is this? The edge
points at a task, and the task has an owner.

One measurement is worth keeping in the record, because it explains why the
symptom looked selective: the `AND` short-circuits. When the task has no
outgoing edges, the index lookup finds nothing and the subquery is never
evaluated — on the two datasets without edges the current form costs 0.02–0.05
ms, the same as the join. The price is paid only by the tasks that actually
have dependencies.

## Idea

Scope both reads through the task's own row, the way the ordering-edge read
already does. `task_dependencies` has no `user_id` column — that is why the
subquery was there — but the dependency row points at a task, and that task
has an owner.

## Decision

`TaskDao.getBlockingEdgeIdsForUser` and
`TaskDao.observeTypedDependenciesForUser` join on the task's primary key:

```sql
SELECT d.depends_on_task_id FROM task_dependencies d
JOIN tasks t ON t.id = d.task_id
WHERE d.task_id = :taskId AND d.verb = :verb AND t.user_id = :userId
```

```sql
SELECT td.task_id, td.depends_on_task_id, td.verb FROM task_dependencies td
JOIN tasks t ON t.id = td.task_id
WHERE td.task_id = :taskId AND t.user_id = :userId
```

No schema change, no DAO signature change, no index added, no change to
dependency ownership, validation or write semantics. The row set is the same:
the harness compared the two forms on every task it read, on all eight
datasets, and they never disagreed; `JvmDatabaseSeamTest` asserts the same
against the real Room database, with a task that carries edges of two verbs, a
task of another user, and an orphan edge whose owning task does not exist.

The change is limited to the two queries this issue measured.

## Rationale

The evidence is a measurement, not an argument about complexity: one read
through the DAO and the identical SQL as raw SQL land in the same range
(186–325 ms against 143–319 ms on the 100k datasets), so the cost is in the
SQL and not in the Room layer. The candidate form was run as an experiment
inside the same harness before any production SQL was touched, in the same
process, on the same restored database, so the before/after pair is one
measurement and not two machines' impressions.

The rewrite is a rewrite of two statements, not a new abstraction, because the
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
row equivalence of the two forms, which does not move with the machine — and a
disagreement fails the run: the report is printed and the program exits with a
non-zero status, so a candidate that answers a different question cannot pass
as a successful measurement.

## Consequences

- `TaskDao.getBlockingEdgeIdsForUser` and
  `TaskDao.observeTypedDependenciesForUser` are joins now. A read of one
  task's edges no longer costs the size of the user's task list: on a 100k
  dataset the blocking read drops from 186 ms through the DAO to 1.09 ms, the
  typed read from 211 ms to 0.30 ms. The remaining cost is the call around the
  query and does not grow with the database.
- The scope of both reads is unchanged in meaning: the owner sees its own
  edges, a foreign user sees none, an edge whose owning task does not exist
  is visible to nobody.
- The cost that is left on a read is the call around it — about 0.3–2.1 ms of
  Room `Flow` machinery and invalidation observer, independent of the size of
  the database. It was not measured separately and was not touched.
- `TaskDao.getTagIdsForUser` asks its scope question in the same old shape. It
  was not part of this issue's measurement, and rewriting it by analogy would
  be a guess; it is the next candidate for the same harness, tracked as
  https://github.com/gazon1/sing/issues/3176.
- The harness is a program in `shared/src/jvmTest`, not a test class: a
  dataset-dependent test would either skip itself or fail for a reason that
  has nothing to do with the claim.

## Links

- Measurement and results: `docs/architecture/dependency-query-performance.md`
- Harness: `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/performance/DependencyQueryPerformanceHarness.kt`
- Gradle task: `:shared:dependencyQueryPerf` in `shared/build.gradle.kts`
- Regression test: `JvmDatabaseSeamTest` in `shared/src/jvmTest/kotlin/com/singularity/todo/core/database/`
- Issue: https://github.com/gazon1/sing/issues/3157
- Follow-up measurement of the tag read: https://github.com/gazon1/sing/issues/3176
- Previous measurement: https://github.com/gazon1/sing/issues/3153
- Dataset generator: https://github.com/gazon1/sing/issues/3090
