---
title: "A detail screen reads the relations of its own rows, not the profile's"
date: 2026-11-24
status: accepted
tags: ["tasks", "performance", "database"]
---

# A detail screen reads the relations of its own rows, not the profile's

## Context

Opening a task detail on the 50k profile exhausted a 192 MiB heap on the realme
RMX3938 before the screen rendered. The recorded `logcat`
(`docs/architecture/large-restore-evidence/profile-50k-crash-older-build.txt`) names
the first allocation the app failed in:

```
at com.singularity.todo.core.database.TaskDao_Impl$observeTagCrossRefs$1.invokeSuspend
```

The earlier attribution — that `AgendaViewModel.latestTasks` could not open the list —
was wrong, and #3148 says so explicitly. Tracing the call path instead of trusting the
attribution found three reads that answer a question about the **whole profile** on a
screen that shows **one task**:

1. `TaskDetailCoordinator` collected `observeByFilter(TaskFilter.All)` and kept the
   result in `allTasks`. It was used for one thing: when a completion write refused,
   the slot picked that task's blockers out of the whole list to name them in the
   confirmation. On the 50k profile that is 50,000 rows, 74,636 tag edges and 5,000
   dependency edges materialised and retained for a confirmation that names at most a
   few tasks — and it happened on every detail open, whether or not a write ever
   refused.
2. `withExtras` collected `observeDependencyCrossRefs(userId)` **twice** — once for the
   blocking projection, once for the follow-up projection — reading and re-grouping the
   same rows per emission.
3. `observeSubtasks` and `observeSeries` are, by construction, a handful of rows, and
   both went through `withExtras`: the subtask list of one task paid for the whole
   profile's tag edges.

The profile-wide read is not wrong in itself — the agenda and the search index show
many rows and need it. It was wrong here, because the rows the screen shows are not the
rows the query returns.

## Idea

- Let the read be as wide as the screen, and no wider. A screen showing one task reads
  that task's relations; a screen showing the profile reads the profile's.
- Answer the blocked-completion guard with a targeted query, since a refused write only
  ever needs to name one task's prerequisites.
- Keep the confirmation's wording and the close rule unchanged: the guard still asks the
  write first and only explains the refusal afterwards.

## Decision

- `TaskRepository.openBlockersOf(taskId)` is the guard's read: the open blocking
  prerequisites of one task, with their titles. It uses `TaskDao.blockingTasksForUser`,
  scoped to the profile in the same query.
- `TaskDetailCoordinator` no longer collects `observeByFilter(TaskFilter.All)` and no
  longer holds `allTasks`; `TaskCompletionSlot` and `TaskChildrenSlot` lost the
  `allTasks` parameter and ask the repository instead.
- `withExtras` (profile-wide) reads the dependency table **once** and projects both the
  blocking and the follow-up map from those rows.
- `withScopedExtras` is the read for rows named by id — `observeSubtasks`,
  `observeSeries` — backed by `observeTagCrossRefsForTasks` /
  `observeDependencyCrossRefsForTasks`. The profile-wide path deliberately does **not**
  use `IN (:ids)`: 50,000 bound variables is not a query SQLite will run.

## Rationale

The cheap fix was the right one, and the issue asked to try it before reaching for
Paging. The three costs above are not inherent to showing a large dataset; they are
reads that fetch rows the screen never shows. Removing them needs no new dependency, no
schema change and no change to what the screens display — the same tags, the same
dependencies, the same confirmation, from a narrower query.

Paging was evaluated and not adopted. The measured failure is not "the list loads
incrementally"; it is "a single-task screen loads the profile". Paging would change the
selector, ordering and invalidation semantics of the list to solve a problem the list
does not have on this device — and the issue forbids adopting it without a measured
bottleneck that justifies it.

The `IN (:ids)` boundary is the one shape worth naming: the same enrichment runs at two
scales, and a single function that picks its query by list size would be a heuristic
that silently changes behaviour at some row count. Two named functions say which
question is being asked.

## Consequences

- The task detail's cost no longer scales with profile size on the relation reads. What
  still scales with profile size is the agenda list, which is the screen built to show
  it, and the search index, which is the screen asked to search it.
- The guard now does a read at confirmation time instead of holding a list. A refusal
  whose prerequisites are gone by the time it explains itself reports a plain save
  failure rather than a confirmation about no one.
- `TaskRepository` grew one targeted method. The fake answers it from its own store, and
  the Room and fake implementations are held to the same contract test.
- The behaviour that must not change — same tags, same dependencies, same refusal, same
  wording — is pinned by `TaskDependencyVerbFilterTest`, the slot tests, and the
  contract tests that run against both implementations.

## Links

- gazon1/sing#3148 — the issue and its acceptance criteria.
- gazon1/sing#3091 — the large-restore verification the crash was found by.
- `docs/architecture/large-restore-results.md` — the measured limit.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/TaskExtras.kt` —
  `withExtras` and `withScopedExtras`.
- `core-database/src/commonMain/kotlin/com/singularity/todo/core/database/Daos.kt` —
  `blockingTasksForUser`, `observeTagCrossRefsForTasks`,
  `observeDependencyCrossRefsForTasks`.
