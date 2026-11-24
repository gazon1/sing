---
title: "The Related Tasks picker scopes its ordering-edge read through a join, not through the user's task list"
date: 2026-11-24
status: accepted
---

# The Related Tasks picker scopes its ordering-edge read through a join, not through the user's task list

## Context

Issue #2981 asked for a measurement of the Related Tasks picker on large
databases: how long the picker takes on 10k/50k/100k tasks and on dependency
graphs of different densities, how many candidates it checks, how many database
calls it makes, and how much of that is the cycle check rather than the search.

The measurement harness (`shared/src/jvmTest/.../performance/RelatedTaskPickerPerformanceHarness.kt`,
run through `:shared:relatedTaskPickerPerf`) restores a dataset produced by the
shared generator of #3090 and drives the picker's own path
(`searchRelatedTaskCandidates`) in four variants: the index read alone, the
`DependencyValidator` walk alone, the whole path, and the whole path with the
cycle check replaced by "always allowed".

What it found was not a slow search. The candidate search costs 4–6 ms on 10k
tasks and 44–47 ms on 100k in the picture a restore leaves, and 26–28 ms and
324–353 ms in the picture that carries the dataset's own order — that is the
FTS index sorting the whole result of `MATCH 'task*'`, and it is the index's own
cost. The cycle check was the problem: on 100 000 tasks, **one** read of one
task's ordering edges cost 351 ms through the DAO and 367 ms as the same raw
SQL. The validator reads those edges once per visited node, so a pick of 30
candidates over a dense graph turned into seconds.

The same read, measured by the same harness in the same session as the
"after" columns, costs 181 ms on `100k-d002` and 144 ms on `100k-d010`; the
first, coldest run on the machine gave the 351 ms above. The machine moves
that number by a factor of two to three, the order of magnitude does not
change, and every "before" and "after" figure in the report comes from one
paired sweep with one method.

The SQL was the reason:

```sql
SELECT depends_on_task_id FROM task_dependencies
WHERE task_id = :taskId
AND task_id IN (SELECT id FROM tasks WHERE user_id = :userId)
```

The `IN (SELECT id FROM tasks WHERE user_id = :userId)` clause is a scope check
on a column that already lives in the row being read — and SQLite materialises
the user's entire task-id list to perform it, on every single read. A replica
of the same schema (50 000 rows) priced it the same way: 59.06 ms per read for
the current query, 49.03 ms for the subquery alone, 0.099 ms for a join.

The cost is conditional, and the condition is what made the symptom look
random. The `AND` short-circuits: when the task has no outgoing edges, the
`task_dependencies` lookup finds nothing and the subquery is never evaluated at
all. On a 100 000-task replica the old query cost 75.36 ms per read for a task
that *has* outgoing edges and 0.118 ms for one that has none. So the price of a
read was O(number of tasks the user owns), paid only by the tasks that actually
have dependencies — which is why a dense dataset hurt and a sparse one did not.

A second replica measurement put the whole path on one curve: the average cost
of one validator read was 5.66 ms on a 10k dataset (density 0.15), 11.4 ms on a
10k dataset where every task has an edge (density 1.0), and 54.6 ms on a 50k
dataset (density 0.5) — the size of the user's task list, every time.

## Idea

Scope the read through the task's own row instead of through the user's task
list. `task_dependencies` has no `user_id` column — that is why the subquery
was there — but the dependency row points at a task, and that task has an
owner. Joining on the task's primary key answers the same question without
materialising a list of every task the user owns.

## Decision

`TaskDao.getOrderingEdgeIdsForUser` joins:

```sql
SELECT d.depends_on_task_id FROM task_dependencies d
JOIN tasks t ON t.id = d.task_id
WHERE d.task_id = :taskId AND t.user_id = :userId
```

The row set is identical to the subquery version (same edges, same scope), the
schema does not change, and the DAO signature does not change. The read drops
from 181 ms to 0.32 ms on `100k-d002` and from 144 ms to 0.17 ms on `100k-d010`
— 566 and 850 times; on the smaller datasets, 62 to 700 times.

Because the rewrite moves the ownership check into a join, the row set it returns
is asserted against the real Room database, not only against the fakes:
`JvmDatabaseSeamTest` inserts a task with edges of two verbs and a task of
another user, and checks that the owner sees its own edges (both verbs for the
ordering graph, only the blocking one for the blocking graph) while a foreign
user sees none of them.

The fix is limited to this one query. Two things were deliberately **not**
changed:

- **The FTS sort.** `FtsDao.searchTaskTitlesByMatch` sorts by `updated_at DESC`
  over every row `MATCH 'task*'` hits, which is why the search costs 324–353 ms
  at 100k. Fixing that means changing the index or the schema, and the issue
  forbids rewriting FTS. It is reported as the remaining cost.
- **The sibling queries with the same shape.** `getBlockingEdgeIdsForUser`,
  `observeTypedDependenciesForUser` and `getTagIdsForUser` ask the same
  question — "whose edges are these?" — through the same subquery. They were
  not measured, and the issue's rule is that a fix stays inside the confirmed
  bottleneck. They are a follow-up with their own measurement, not a guess.

The validator's per-node walk was left alone as well: after the query fix a
read costs ~0.1–0.4 ms, so the walk is no longer what is expensive. Batching the
frontier by level would have bought almost nothing on the narrow closures the
generated graphs actually have.

## Rationale

The evidence for the bottleneck was two independent experiments: an in-app probe
(DAO read vs. the identical raw SQL, on the restored database) and a standalone
SQLite replica with the same schema. Both put the cost in the SQL, not in the
Room layer or the validator's control flow — the DAO read and the raw SQL
land in the same range (181 ms vs 185 ms on `100k-d002`, 351 ms vs 367 ms on the
coldest run). The report was written from those numbers, not from a complexity
argument.

The change is a rewrite of one query rather than a new abstraction, because the
shape of the cost was local: one statement, one table, one owner column. The
alternative — passing the user's task ids into the DAO as a parameter — would
have kept the list-building cost and pushed it up a layer.

The latency numbers are reported, not asserted. The harness asserts the counts
that do not depend on the machine (the fetch expansion stays bounded, the
result count stays under its limit); a clock assertion on a shared machine
would be a flaky test wearing a benchmark's clothes. It also asserts that each
scenario still measures what it is for: every scenario must find the
candidates it searches for, and the expansion scenario must really expand (at
least two index reads) — otherwise a search that returned nothing would satisfy
every bound and report excellent numbers for a workload it never ran. The percentiles themselves
are nearest-rank — the `ceil(fraction × n)`-th smallest of the ten iterations,
without interpolation — so every published p50 is a duration the harness
actually measured, and the "before" and "after" columns come from one paired
sweep with one method.

The harness restores the archive twice and measures both pictures of the
restored dataset: the state the restore actually leaves (every row with the
same `updated_at`) and the state of a working database (the dataset's own
order put back into `updated_at`). The picker's candidate order — and with it
the price of the index sort — follows `updated_at`, so a single picture would
have been a claim about a state the app is not in after a restore. The
bottleneck, the effect of the fix and the counters are the same in both
pictures; what differs is the order of the candidates and the cost of sorting
them.

## Consequences

- `TaskDao.getOrderingEdgeIdsForUser` is a join now. Any future index on
  `tasks(user_id)` would no longer change its cost — the list it used to build
  is gone.
- The Related Tasks picker's cost on 100 000 tasks is now dominated by the FTS
  sort, not by the cycle check. In the picture the restore actually leaves
  (`100k-d002`): the full path p50 is 44.60 ms, of which the index read alone is
  44.51 ms — before the fix the validator alone cost 592.65 ms. Where the graph
  is dense the difference is larger: `10k-d100` full path p50 187.61 ms →
  10.49 ms, validator 192.55 ms → 9.86 ms. In the picture where the rows carry
  the dataset's own order the same scenario reads 1172.40 ms → 41.95 ms,
  validator 1147.46 ms → 18.08 ms.
- `DependencyValidator`'s behaviour is unchanged: the same edges are read, in
  the same number of reads. The picker's cost tests, which count those reads,
  stay green.
- The restore flattens `updated_at` for every row (`BackupImporter.applyRows`
  writes `updated_at = now`), which makes the picker's `ORDER BY updated_at
  DESC` meaningless on restored data. The harness therefore measures **both**
  pictures of the same dataset: the state the restore actually leaves, and the
  state of a working database (the dataset's own order put back into
  `updated_at`). The conclusion is the same in both — the fix moves the cost of
  the picker from the cycle check to the index sort. The restore itself was not
  changed. It is recorded as a finding.
- The harness is a program in `shared/src/jvmTest`, not a test class: a
  dataset-dependent test would either skip itself or fail for a reason that has
  nothing to do with the claim, and `scripts/check-test-runs.py` forbids skips.

## Links

- Measurement and results: `docs/architecture/related-task-picker-performance.md`
- Harness: `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/performance/RelatedTaskPickerPerformanceHarness.kt`
- Gradle task: `:shared:relatedTaskPickerPerf` in `shared/build.gradle.kts`
- Issue: https://github.com/gazon1/sing/issues/2981
- Dataset generator: https://github.com/gazon1/sing/issues/3090
