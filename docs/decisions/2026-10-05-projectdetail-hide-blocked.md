---
title: "ProjectDetail 'Hide blocked' resolves blocking against the unfiltered list"
date: 2026-10-05
tags: [projects, tasks, filtering]
status: accepted
---

## Context

`ProjectDetailViewModel` filters the task list by completion only. Tasks blocked by unfinished dependencies (`Task.dependsOn` with an incomplete referent) sit alongside actionable ones, with no way to separate them.

The AGENDA already renders blocked tasks correctly — `AgendaEvaluator.kt:55` sets `isBlocked` by calling `TaskComputed.isBlocked(task, tasks)` against the **complete** task list — so the predicate exists and the precedent for how to call it is written down.

The obvious implementation composes a second predicate onto the existing one:

```kotlin
val visibleTasks = tasks.filter { task ->
    (!hideCompleted || task.completedAt == null) && (!hideBlocked || !TaskComputed.isBlocked(task, tasks))
}
```

That is wrong in a way that only shows up when the two filters are used together.

## Idea

Filter completed first, then resolve blocking against what survives. The blocked task then looks free — because the dependency that blocks it is no longer in the list to be found.

The same trap exists in the reverse direction and in a subtler form: `ProjectDetailBody` truncates the rendered list to `visibleTasks.take(5)`. Any filtering computed against that truncated list would make blocking depend on which five tasks happened to sort first.

## Decision

Blocking is resolved against the **full, unfiltered** project task list, before any filter is applied:

```kotlin
val blockedIds = tasks
    .filter { TaskComputed.isBlocked(it, tasks) }
    .mapTo(mutableSetOf()) { it.id }
val visibleTasks = tasks.filter { task ->
    (!hideCompleted || task.completedAt == null) && (!hideBlocked || task.id !in blockedIds)
}
```

The two toggles are folded into a single `TaskVisibility` value rather than combined as a sixth flow, because the `combine` in `init` is already at kotlinx's five-argument limit — its own comment records that a vararg call would collapse to `Array<Any?>`.

`hideBlocked` defaults to `false`. `true` would silently remove tasks the user could previously see.

## Rationale

Blocking is a property of a task *in the world*, not of a task in a filtered list. The dependency that blocks a task does not stop blocking it because a different filter hid it. Computing `blockedIds` once, up front, from the unfiltered list makes the two filters independent and order-free, which is also what makes them composable.

## Consequences

- `blockedIds` is O(n·m) as written (each `isBlocked` builds an index of `allTasks`). Fine at project scale; worth revisiting if projects ever hold thousands of tasks.
- The filter cannot be pushed into SQL without the same care — a SQL `NOT EXISTS` over `dependsOn` would actually be *more* correct here, since it never sees the filtered list at all.
- `hideBlocked` rides the existing `TaskVisibility` input, so the arity limit is not exceeded and the state stays a single atomic snapshot.
- A test pins the ordering trap explicitly: a task blocked by a *completed* dependency stays blocked when `hideCompleted` is on.

## Links

- `feature/projects/presentation/viewmodel/ProjectDetailViewModel.kt` — `TaskVisibility`, `blockedIds`
- `feature/agenda/domain/logic/AgendaEvaluator.kt:55` — the precedent
- `2026-09-18-task-dependencies` — the blocking predicate itself