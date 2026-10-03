# Navigation Architecture Specification

> This document is the authoritative reference for Nav3 navigation architecture.
> It is a living document — any change to navigation behavior MUST update this spec.

## Overview

The app uses **Navigation 3 (Nav3)** with a **multi-back-stack** pattern:

- **Outer graph**: `AppDestination` routes (tabs + menu entries)
- **Nested graphs**: `TasksRoute`, `AgendaStartRoute`, `CalendarRoute`, `NotesRoute`, `ProjectsRoute`, `SearchRoute`, `SettingsRoute`
- **Renderer**: `NavDisplay` (shared Android + JVM component) renders all active stacks

```
AppDestination (outer)
├── AgendaGraph
│   └── AgendaStartRoute: Today, Inbox, Upcoming, Project(...), Tag(...), SavedAgendaList, SavedAgendaResults, SavedAgendaEdit, SavedAgendaCreate
├── TasksGraph
│   └── TasksRoute: Create(...), Detail(...)
├── CalendarGraph
│   └── CalendarRoute: Month(...), Day(...)
├── NotesGraph
│   └── NotesRoute: List, Preview(...), Editor(...)
├── ProjectsGraph
│   └── ProjectsRoute: List, Detail(...), Editor(...)
└── SettingsGraph
    └── SettingsRoute: Top, Appearance, Notifications, ...
```

---

## Rule 1: `NavDisplay` dispatches on `backStack.top`, not `start`

`NavDisplay` renders the entry at `backStack.top`. The `start` parameter is used only to **seed** the back stack.

**Consequence:** If `backStack` is seeded with `Create(null)` but the caller passes `Detail(taskId)` as `start`, the screen shown is `Create(null)` — not `Detail`.

**Correct pattern — seed and start must match, or seed must be updated:**

```kotlin
// WRONG: NavDisplay will render Create(null) despite start = Detail
entry<AppDestination.TasksGraph> { route ->
    val tasksStack = rememberInMemoryNavBackStack(TasksRoute.Create(null))
    TasksNavGraph(
        start = route.start.toTasksRoute(route.initialDueDate),  // may be Detail
        backStack = tasksStack,
    )
}

// RIGHT: when start differs from seed, add to stack before rendering
entry<AppDestination.TasksGraph> { route ->
    val tasksStack = rememberInMemoryNavBackStack(TasksRoute.Create(null))
    val startRoute = route.start.toTasksRoute(route.initialDueDate)
    if (startRoute is TasksRoute.Detail) {
        tasksStack.add(startRoute)  // NavDisplay now renders Detail
    }
    TasksNavGraph(
        start = startRoute,
        backStack = tasksStack,
    )
}
```

**When seed = start (same type):** No `add()` needed.

```kotlin
// AgendaNavGraph — seed always equals route.start by construction
entry<AppDestination.AgendaGraph> { route ->
    val agendaStack = rememberInMemoryNavBackStack(route.start)  // seed = start
    AgendaNavGraph(
        start = route.start,  // identical to seed
        backStack = agendaStack,
    )
}
```

---

## Rule 2: Cross-graph navigation via `onExitGraph`

Nested graphs communicate with the outer graph via `onExitGraph`.

```kotlin
val navigator = TasksNavigator(stack) { dest: AppDestination? ->
    // null = go back in outer graph
    // AppDestination.ProjectDetail(...) = navigate to project
    dest?.let { outerNav.navigate(it) } ?: outerNav.goBack()
}
```

**All `*Navigator` classes use `onExitGraph` for cross-graph navigation:**

| Navigator | Method | Destination |
|-----------|--------|-------------|
| `AgendaNavigator` | `openTask(taskId)` | `TasksGraph(Detail)` |
| `AgendaNavigator` | `openCreateInSection(sectionId)` | `TasksGraph(Create)` |
| `CalendarNavigator` | `openTask(taskId)` | `TasksGraph(Detail)` |
| `CalendarNavigator` | `openCreateTask(date)` | `TasksGraph(Create, initialDueDate)` |
| `TasksNavigator` | `openProject(projectId)` | `ProjectDetail` |
| `TasksNavigator` | `openNote(noteId)` | `NotesGraph(Preview)` |
| `NotesNavigator` | `openTask(taskId)` | `TasksGraph(Create)` — wikilinks create, not edit |

**No `onExitGraph` for same-graph navigation** — use `backStack.add(Route)`.

---

## Rule 3: In-memory stacks on JVM, saved-state on Android

**JVM Desktop:** `rememberInMemoryNavBackStack(seed)` — plain `NavBackStack(seed)` kept in memory. No process death, no saved state needed.

**Android:** `rememberNavBackStack(navSavedStateConfig(), start)` — restores state from `SavedStateConfiguration` on process death.

Both are wrapped in a platform-specific factory so the outer graph code is platform-agnostic.

---

## Rule 4: `JvmNavEntries` owns all nested graph entry creation

All nested graph entries for JVM Desktop are created in `shared/src/jvmMain/kotlin/com/singularity/todo/feature/nav/JvmNavEntries.kt` via the `createJvmEntryProvider` function.

**Structure:**

```kotlin
@Composable
fun createJvmEntryProvider(nav: NavCallbacks): (AppDestination) -> NavEntry<AppDestination> = entryProvider {
    // Top-level tabs
    entry<AppDestination.Agenda> { AgendaNavGraph(...) }
    entry<AppDestination.Tasks> { TasksNavGraph(...) }
    // ...
    // Nested graph entries
    entry<AppDestination.TasksGraph> { route -> ... }
    entry<AppDestination.AgendaGraph> { route -> ... }
    entry<AppDestination.CalendarGraph> { route -> ... }
    entry<AppDestination.NotesGraph> { route -> ... }
    entry<AppDestination.ProjectsGraph> { route -> ... }
}
```

**Platform entry providers** (androidMain) use the same structure with platform-specific NavGraph implementations.

---

## Rule 5: Back navigation respects stack depth

Every `*Navigator.back()` follows the same contract:

```kotlin
open fun back() {
    if (backStack.size <= 1) {
        onExitGraph(null)  // at root — exit the graph
    } else {
        backStack.removeLastOrNull()  // pop one level
    }
}
```

`size <= 1` means only the seed entry remains. Popping it would leave an empty stack, so we exit instead.

---

## Rule 6: Tab reselect emits `reselectEvents`

Tapping the **active tab** emits a `reselectEvents` event instead of switching tabs. Screens use this to reset scroll position or refresh content.

**Implementation:** `Nav3State.onTabTapped(route)`:
- If `route == topLevelRoute` → emit reselect event
- Otherwise → switch to new tab

Screens that need reselect handling collect `navCallbacks.reselectEvents` (or `Nav3State.reselectEvents`).

---

## Rule 7: Nested graph `start` route conversion

Outer destinations (`AppDestination.TasksGraph(start = TasksStartRoute.Detail(...))`) are converted to inner routes (`TasksRoute.Detail(...)`) via `toTasksRoute(initialDueDate)`:

```kotlin
private fun AppDestination.TasksStartRoute.toTasksRoute(initialDueDate: LocalDate?): TasksRoute =
    when (this) {
        is AppDestination.TasksStartRoute.Create ->
            TasksRoute.Create(initialDueDate)
        is AppDestination.TasksStartRoute.Detail ->
            TasksRoute.Detail(TaskId.fromString(taskId))
        is AppDestination.TasksStartRoute.Inbox -> TasksRoute.Create(null)     // deprecated
        is AppDestination.TasksStartRoute.Upcoming -> TasksRoute.Create(null)   // deprecated
    }
```

**Deprecated variants (`Inbox`, `Upcoming`)** resolve to `Create(null)` and exist for backwards compatibility with existing deep links. New navigation always uses explicit `Create` or `Detail`.

---

## Rule 8: No `stateIn` in ViewModels — use `MutableStateFlow + scope.launch { }`

Navigation state is held in `NavBackStack` (observable list). ViewModels that need to react to navigation events should collect the stack via `scope.launch { stack.collect { ... } }`, not `stateIn`.

See `singularity-todo-testable-vm` skill and ADR `2026-09-27-vm-koin-scoping-retired.md`.

---

## Verification

Run the desktop navigation tests to verify this spec is upheld:

```bash
./gradlew :desktopApp:test --tests 'com.singularity.todo.feature.flows.agenda.OpenTaskFromAgendaFlowTest'
./gradlew :desktopApp:test --tests 'com.singularity.todo.feature.flows.tasks.SetDueDateFlowTest'
./gradlew :desktopApp:test --tests 'com.singularity.todo.feature.flows.tasks.SetPriorityFlowTest'
./gradlew :shared:jvmTest --tests 'com.singularity.todo.arch.ArchitectureTest'
```

The Konsist rule `nav3 backStack seed mismatch requires explicit add for Detail routes` enforces Rule 1 automatically.

---

## Related Documents

- [ADR: Nav3 backStack.top vs start parameter dispatch](docs/decisions/2026-10-03-nav3-backstack-top-vs-start-dispatch.md)
- [ADR: Single sealed NavKey root](docs/decisions/2026-09-29-single-sealed-navkey-root.md)
- [ADR: Nav3 type asymmetry](docs/decisions/2026-09-16-nav3-type-asymmetry-adr.md)
- [Nav3SavedState.kt](../shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/Nav3SavedState.kt)
- [JvmNavEntries.kt](../shared/src/jvmMain/kotlin/com/singularity/todo/feature/nav/JvmNavEntries.kt)
