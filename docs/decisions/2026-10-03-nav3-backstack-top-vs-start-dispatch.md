---
title: Nav3 backStack.top vs start parameter dispatch mismatch in JvmNavEntries
status: accepted
date: 2026-10-03
tags: [navigation, desktop, nav3, jvm]
---

# Context

Desktop UI tests `SetDueDateFlowTest` and `SetPriorityFlowTest` were failing. When a task was clicked in the Agenda list, the task editor opened but showed empty/default values (placeholder text, no priority) even though the task in the database had `dueDate=2026-10-03` and `priority=None`.

The `TaskDetailCoordinator` was initializing with empty state because `TaskDetailScreen` was never rendered — `TaskCreateScreen` was shown instead.

# Idea

The root cause is a dispatch mismatch in `JvmNavEntries.kt`'s `TasksGraph` entry:

```kotlin
entry<AppDestination.TasksGraph> { route ->
    val tasksStack: NavBackStack<TasksRoute> = rememberInMemoryNavBackStack(TasksRoute.Create(null))
    TasksNavGraph(
        start = route.start.toTasksRoute(route.initialDueDate),  // Detail or Create
        ...
        backStack = tasksStack,  // seeded with Create(null)
    )
}
```

`NavDisplay` (the navigation renderer used on both Android and JVM) renders entries based on `backStack.top`, **not** the `start` parameter. The `tasksStack` was seeded with `Create(null)`, so `NavDisplay` always rendered `TaskCreateScreen` on initial composition, even when `start = TasksRoute.Detail(...)`.

The `start` parameter was passed to `TasksNavGraph` and used to initialize the coordinator's `taskId`, but the screen itself was wrong because `NavDisplay` uses `backStack.top`.

# Decision

When the `start` route is `Detail`, add it to the `backStack` before rendering so `NavDisplay` dispatches to the correct entry:

```kotlin
entry<AppDestination.TasksGraph> { route ->
    val tasksStack: NavBackStack<TasksRoute> = rememberInMemoryNavBackStack(TasksRoute.Create(null))
    val startRoute = route.start.toTasksRoute(route.initialDueDate)
    // NavDisplay renders based on stack.top, not the start parameter. When starting
    // with Detail, add it to the stack so the correct entry is rendered immediately.
    if (startRoute is TasksRoute.Detail) {
        tasksStack.add(startRoute)
    }
    TasksNavGraph(
        start = startRoute,
        onExitGraph = { dest ->
            when (dest) {
                is AppDestination.ProjectDetail -> nav.navigate(dest)
                else -> nav.goBack()
            }
        },
        backStack = tasksStack,
    )
}
```

This was applied in commit `6e07f5f7` (merge) and fixed the 3 failing desktop UI tests.

# Rationale

`NavDisplay` is a shared renderer used by both Android and JVM. It uses `backStack.top` as the single source of truth for which entry is current. The `start` parameter exists for `rememberNavBackStack` on Android (to restore saved state), but `rememberInMemoryNavBackStack` ignores it after the initial seed — the stack itself is the truth.

For `Create`, the pattern works because `start` and `stack.seed` are both `Create(null)`. For `Detail`, the mismatch surfaces because `start = Detail(...)` but `stack.top = Create(null)`.

# Consequences

- `AgendaNavigator.openTask(taskId)` → `TasksGraph(Detail)` now correctly opens `TaskDetailScreen` on JVM Desktop ✓
- `CalendarNavigator.openTask(taskId)` → `TasksGraph(Detail)` also fixed (same pattern) ✓
- `NotesNavigator.openTask()` navigates to `Create` (not `Detail`) — wikilinks in notes create a new task, so this is correct by design
- The `AgendaNavGraph` entry uses `start = route.start` (seed = route.start by construction) and works correctly without an explicit add
- Same-seed entries (`CalendarNavGraph`) don't need the fix

# Policy

**Rule:** When creating a nested graph entry in `JvmNavEntries.kt`, if the `start` route type differs from the `rememberInMemoryNavBackStack(seed)` type, you MUST add `startRoute` to `backStack` before rendering the graph.

**Checklist for new graph entries:**

1. Does `rememberInMemoryNavBackStack(...)` use the same route type as `start = ...`? → No action needed.
2. Does `start` resolve to `Detail` when seed is `Create`? → Add `if (startRoute is TasksRoute.Detail) { backStack.add(startRoute) }`.
3. Does `AgendaNavGraph` entry exist? → Ensure `start = route.start` (seed always equals route.start).
4. Adding a new route type to an existing graph? → Apply rule 1-2 above; update this ADR.

**Enforcement:** The Konsist test `nav3 backStack seed mismatch requires explicit add for Detail routes` in `ArchitectureTest.kt` enforces rule #2. It will fail if a future change introduces a `Create`-seeded stack with a `Detail` start without the corresponding `backStack.add()` call.

# Links

- Fix applied in `shared/src/jvmMain/kotlin/com/singularity/todo/feature/nav/JvmNavEntries.kt`
- `NavDisplay` dispatch: `nav3.ui.NavDisplay` source
- `rememberInMemoryNavBackStack`: `shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/Nav3SavedState.kt`
