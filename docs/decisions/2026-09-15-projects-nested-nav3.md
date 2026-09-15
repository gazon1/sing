---
title: "Projects feature: nested Nav3 graph with ProjectsNavigator"
date: 2026-09-15
tags: [nav3, navigation, koin, refactor, projects]
---

## Context

The projects feature screens (`ProjectsScreen`, `ProjectEditorScreen`, `ProjectDetailScreen`) received navigation callbacks (`onNavigateToProject`, `onNavigateToCreateProject`, `onBack`, `onNavigateToTasks`, `onNavigateToTask`) as function parameters. This required every intermediate composable to thread these callbacks even when they didn't use them, and made screen-level previews impossible without providing fake callbacks.

After the tasks feature was migrated to a nested Nav3 graph (`TasksNavGraph` + `TasksNavigator` + `LocalTasksNavigator`), the same pattern is applied to projects for consistency and to fix the latent VM scoping bug for `ProjectEditorViewModel(projectId)` and `ProjectDetailViewModel(projectId)`.

## Idea

Mirror the tasks feature pattern:
1. `ProjectsRoute` sealed interface (`List`, `Editor(projectId?)`, `Detail(projectId)`) for the nested graph.
2. `ProjectsNavigator` open class with `openDetail`, `openEditor`, `openTasks`, `openTask`, `back`, `closeGraph`.
3. `LocalProjectsNavigator` CompositionLocal — screens read `current` instead of receiving callbacks.
4. `ProjectsNavGraph` expect/actual composable with `rememberViewModelStoreNavEntryDecorator` on Android.
5. `ProjectsGraph(start: ProjectsStartRoute)` + `ProjectsStartRoute` in `AppDestination` for future deep-link flexibility.

## Decision

### Nested NavHost

```kotlin
// shared/src/commonMain/.../projects/presentation/nav/ProjectsNavGraph.kt
@Composable
expect fun ProjectsNavGraph(
    start: ProjectsRoute,
    onExitGraph: (AppDestination?) -> Unit,
    modifier: Modifier = Modifier,
)
```

Android implementation (`ProjectsNavGraph.android.kt`):
- `rememberNavBackStack(SavedStateConfiguration { }, start)`
- `CompositionLocalProvider(LocalProjectsNavigator provides navigator)`
- `BackHandler(enabled = backStack.size <= 1) { onExitGraph(null) }`
- `NavDisplay` with `entryDecorators = listOf(rememberViewModelStoreNavEntryDecorator())` — **fixes VM scoping bug**
- `entryProvider`: `List → ProjectsScreen()`, `Editor → ProjectEditorScreen(projectId)`, `Detail → ProjectDetailScreen(projectId)`

JVM implementation (`ProjectsNavGraph.jvm.kt`): same structure, no `BackHandler`, no `rememberViewModelStoreNavEntryDecorator`.

### ProjectsRoute

```kotlin
sealed interface ProjectsRoute : NavKey {
    data object List : ProjectsRoute
    data class Editor(val projectId: ProjectId? = null) : ProjectsRoute
    data class Detail(val projectId: ProjectId) : ProjectsRoute
}
```

Not `@Serializable` — the nested graph uses an empty `SavedStateConfiguration` (routes kept in memory only).

### AppDestination

Added `ProjectsGraph(start: ProjectsStartRoute)` and `ProjectsStartRoute` for future deep-link support. Existing `Plans`, `ProjectEditor`, `ProjectDetail` remain as entry-points that delegate to `ProjectsNavGraph`.

```kotlin
@Serializable data class ProjectsGraph(
    val start: ProjectsStartRoute = ProjectsStartRoute.List,
) : AppDestination

@Serializable sealed interface ProjectsStartRoute : NavKey {
    @Serializable data object List : ProjectsStartRoute
}
```

### ProjectsNavigator

```kotlin
open class ProjectsNavigator(
    private val backStack: NavBackStack<ProjectsRoute>,
    private val onExitGraph: (AppDestination?) -> Unit,
) {
    open fun openDetail(id: ProjectId)
    open fun openEditor(id: ProjectId? = null)
    open fun openTasks(projectId: ProjectId)  // cross-feature hop
    open fun openTask(taskId: TaskId)          // cross-feature hop
    open fun back()
    open fun closeGraph()
}
```

`open class` — preview subclasses override navigation methods to no-ops.

### Routing in ProjectDetailIntent

Per earlier ADR (`2026-09-09-project-detail-intent-refactor`), sheet-openers (`OpenColorSheet`, `OpenIconSheet`, etc.) remain as `Routing` intents. Only the two navigation intents are removed from `ProjectDetailActions` — `onNavigateToTasks` and `onNavigateToTask` — replaced by `ProjectsNavigator.openTasks()` / `openTask()` calls directly in `ProjectDetailContent`.

`ProjectDetailUiEvent.NavigateBack` continues to be handled by `CollectEvents` → `navigator.back()`.

### Preview support

`ProjectsPreviewWrapper` provides `PreviewProjectsNavigator` (all methods no-op) via `CompositionLocalProvider`. Preview composables for `ProjectsScreen`, `ProjectEditorScreen`, and `ProjectDetailScreen` are wrapped accordingly.

### VM scoping fix

`rememberViewModelStoreNavEntryDecorator` on Android fixes the Koin bug where `koinViewModel { parametersOf(projectId) }` resolves `LocalViewModelStoreOwner` to `ComponentActivity` instead of the `NavEntry`. This fixes the latent bug for `ProjectDetailViewModel(projectId)` and `ProjectEditorViewModel(projectId)`.

## Rationale

**Symmetry with tasks:** Both features now use identical nested-graph + navigator + CompositionLocal patterns.

**VM scoping correctness:** `rememberViewModelStoreNavEntryDecorator` is the official nav3-recipes path for per-entry VM scoping with Koin. Without it, navigating `ProjectDetail(X)` → back → `ProjectDetail(Y)` would show X's state.

**Minimal API surface:** Screens go from 3–5 callback parameters to 0. Screens use `LocalProjectsNavigator.current` directly.

**Backwards-compatible:** `AppDestination.ProjectEditor` and `ProjectDetail` remain valid entry-points (for deeplinks) — they simply resolve to `ProjectsNavGraph` now. `TasksNavigator.openProject` continues to send `AppDestination.ProjectDetail(...)` which routes correctly.

**Cross-feature navigation:** `ProjectsNavigator.openTasks()` → `onExitGraph(AppDestination.TasksByProject(...))` → outer graph handles the cross-feature hop. Symmetric with `TasksNavigator.openProject`.

## Consequences

### Positive

- All 3 projects screens use `LocalProjectsNavigator` — no callback parameters.
- `ProjectDetailViewModel(projectId)` and `ProjectEditorViewModel(projectId)` now have correct per-entry VM scoping on Android.
- Feature isolation: `ProjectsNavGraph` is self-contained and could be ported to iOS or other shells.
- Cross-feature navigation between projects and tasks uses type-safe `AppDestination` hops.

### Negative

- 8 new files (nav package under projects feature) + 2 new ADR records.
- Additional level of indirection for new developers: "where am I?"

### Intent/ADR deviations from prior decisions

This ADR explicitly deviates from two prior decisions:

1. **`2026-09-09-project-detail-intent-refactor`** — sheet-openers (`Routing.OpenColorSheet`, etc.) were kept as `Routing` intents. The plan originally proposed removing the entire `Routing` hierarchy, but the user confirmed keeping sheet-openers as routing intents (they are UI state transitions, not domain operations).

2. **`2026-09-09-preview-with-koin-helper`** — `ProjectDetailActions` still carries sheet-openers as methods, and `ProjectDetailContent` still accepts callback parameters for previews. The `PublicScreen`/`PrivateContent` pattern is maintained; the only change is removal of `onNavigateToTasks` and `onNavigateToTask` from the actions bundle.

## Caveats

### `NavBackStack<T>` sealed interface type inference on JVM

The `NavBackStack` constructor with a vararg of sealed interface instances can fail type inference on the JVM:

```kotlin
// May fail to infer T on JVM for sealed interface NavKey:
NavBackStack(TasksRoute.Inbox(), TasksRoute.Inbox())

// Workaround: explicit type parameter
NavBackStack<TasksRoute>(TasksRoute.Inbox(), TasksRoute.Inbox())
```

This is a Kotlin/JVM limitation with sealed interfaces in generic varargs. Any new `ProjectsRoute` or `TasksRoute` sealed interface route must use the explicit `<T>` form in `PreviewProjectsNavigator` / `PreviewTasksNavigator`. See `ProjectsPreviewHelpers.kt` for the current workaround pattern.

## Links

- `2026-09-14-tasks-feature-nested-nav3.md` — tasks feature migration (identical pattern)
- `2026-09-14-nav3-vm-store-decorator-fix.md` — VM scoping bug + decorator fix
- `2026-09-09-project-detail-intent-refactor.md` — routing vs domain intent split (sheet-openers remain routing)
- `philipplackner/Nav3Guide` — `NavigationRoot.kt` pattern
- `androidx.navigation3:nav3-recipes/passingarguments/viewmodels/koin`
