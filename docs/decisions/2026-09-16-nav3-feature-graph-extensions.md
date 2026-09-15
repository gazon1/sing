---
title: "NotesNavGraph start parameter, TasksStartRoute.Detail, AppDestination additions"
date: 2026-09-16
tags: [navigation, nav3, tasks, notes]
---

## Context

Three gaps prevented proper deep-linking and cross-feature navigation without callback threading:

1. `NotesNavGraph` had no `start` parameter — it always opened on `NotesRoute.List`. Deep-links from Search results (note tap) would land on the list, not the note preview.
2. `TasksStartRoute` had no `Detail` variant — the FAB, Archive, and Search all needed to open a task detail from outside the tasks graph, but only `Create` existed.
3. `ProjectsNavigator.openTask(taskId)` still used the deprecated `AppDestination.TaskDetail(taskId.value)` instead of `TasksGraph(TasksStartRoute.Detail(...))`.

## Decision

### 1. AppDestination additions

```kotlin
// TasksStartRoute — added Detail
@Serializable data class Detail(val taskId: String) : TasksStartRoute

// NotesStartRoute + NotesGraph (new outer destination for deep-links)
@Serializable sealed interface NotesStartRoute : NavKey {
    @Serializable data object List : NotesStartRoute
    @Serializable data class Preview(val noteId: String) : NotesStartRoute
}
@Serializable data class NotesGraph(
    val start: NotesStartRoute = NotesStartRoute.List,
) : AppDestination { override val title = "Notes" }

// ProjectsStartRoute — added Editor
@Serializable data class Editor(val projectId: String? = null) : ProjectsStartRoute
```

### 2. NotesNavGraph accepts `start` (not yet implemented)

The sealed interface `NotesStartRoute` is added to `AppDestination.kt` but `NotesNavGraph` itself still uses `navCallbacks` only. A future commit will add the `start` parameter, following the same pattern as `TasksNavGraph`.

### 3. TasksNavGraph maps `TasksStartRoute.Detail`

`AndroidNavEntries` / `JvmNavEntries` now have a `TasksGraph` entry that converts `TasksStartRoute` to `TasksRoute`:

```kotlin
entry<AppDestination.TasksGraph> { route ->
    TasksNavGraph(
        start = route.start.toTasksRoute(route.initialDueDate),
        onExitGraph = { ... },
    )
}

private fun TasksStartRoute.toTasksRoute(initialDueDate: LocalDate?): TasksRoute = when (this) {
    is TasksStartRoute.Inbox -> TasksRoute.Inbox()
    is TasksStartRoute.Today -> TasksRoute.Today()
    is TasksStartRoute.Create -> TasksRoute.Create(initialDueDate)
    is TasksStartRoute.ByProject -> TasksRoute.ByProject(ProjectId.fromString(projectId))
    is TasksStartRoute.Detail -> TasksRoute.Detail(TaskId.fromString(taskId))
}
```

### 4. ProjectsNavigator.openTask updated

```kotlin
// Before
open fun openTask(taskId: TaskId) {
    onExitGraph(AppDestination.TaskDetail(taskId.value))
}

// After
open fun openTask(taskId: TaskId) {
    onExitGraph(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail(taskId.value)))
}
```

## Rationale

- `TasksStartRoute.Detail` enables the FAB, Archive, and Search to open task details via `TasksGraph` without threading callbacks.
- `NotesStartRoute` + `NotesGraph` establishes the two-type pattern (outer `@Serializable` start route / inner `NavKey`-only route) matching how `TasksStartRoute` / `TasksRoute` already work.
- `noteId: String` in `NotesStartRoute.Preview` mirrors `taskId: String` in `TasksStartRoute.Detail` — consistent with the pattern.
- Updating `ProjectsNavigator.openTask` removes the last consumer of the deprecated `TaskDetail` route.

## Consequences

- `AppDestination.TaskDetail` and `TaskDetailCreate` remain `@Deprecated` — they can be deleted in a follow-up cleanup commit.
- `ProjectsNavGraph` in `NavEntries` now maps `ProjectsStartRoute.Editor` to `ProjectsRoute.Editor`.

## Links

- `AppDestination.kt` (TasksStartRoute.Detail, NotesStartRoute, NotesGraph, ProjectsStartRoute.Editor)
- `AndroidNavEntries.kt`, `JvmNavEntries.kt` (TasksGraph entry, conversion helpers)
- `ProjectsNavigator.kt` (openTask updated)
