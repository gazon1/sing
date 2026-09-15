---
title: "Nav3 post-migration fixes — NotesNavGraph start, preview wrappers, FAB cleanup"
date: 2026-09-16
tags: [navigation, nav3]
---

## Context

After the main Nav3 migration wave (Phases A–H), a review identified six issues that required fixes before merging.

## Decisions

### 1. `NotesNavGraph` accepts `start` parameter

`NotesNavGraph` always opened on `NotesRoute.List`, ignoring any deep-link target. The `start: NotesRoute` parameter was added to the expect/actual signature:

```kotlin
// commonMain
@Composable expect fun NotesNavGraph(navCallbacks: NavCallbacks, start: NotesRoute = NotesRoute.List, modifier: Modifier)

// Android + JVM actuals
actual fun NotesNavGraph(navCallbacks, start, modifier) {
    val backStack = rememberNavBackStack(savedStateConfig, start) // ← was List, now is start
    ...
}
```

`entry<AppDestination.NotesGraph>` in NavEntries converts `AppDestination.NotesStartRoute → NotesRoute` and passes it as `start`.

### 2. Preview wrappers for `SearchScreen` and `AccountSettingsScreen`

`LocalSearchNavigator.current` and `LocalSettingsNavigator.current` are only available inside their respective NavGraphs. Previews that tried to use them crashed at composition time.

Fix: `PreviewSearchNavigator` and `PreviewSettingsNavigator` — open subclasses with no-op implementations:

```kotlin
class PreviewSearchNavigator : SearchNavigator(onExitGraph = {}) {
    override fun openTask(taskId: TaskId) { }
    override fun openNote(noteId: NoteId) { }
    override fun openProject(projectId: ProjectId) { }
}
```

Previews use `PreviewSearchNavigator()` directly instead of `LocalSearchNavigator.current`.

### 3. No duplicate `@Composable` annotations found

Checked all newly created nav files — all annotations are correct. No fixes needed.

### 4. `ProjectsStartRoute.Editor` null safety

`projectId?.let { ProjectId.fromString(it) }` is already safe. The `?.let` ensures `fromString` is never called with `null`. No code change needed.

### 5. Deprecated `TaskDetail` / `TaskDetailCreate` already removed

NavEntries no longer contain `entry<AppDestination.TaskDetail>` or `entry<AppDestination.TaskDetailCreate>`. The `@Deprecated` annotations on the route classes remain; cleanup can happen in a follow-up.

### 6. Notes FAB removed — meaningless self-navigation

`AppDestination.Notes → NotesNavGraph` from the shell FAB was a no-op: the user was already navigating within the Notes graph context. NotesNavGraph has its own create-note button.

```kotlin
// Before
AppDestination.Notes -> FabAction("Add note") { navigator.navigate(AppDestination.Notes) }

// After — Notes has its own creation UI, no shell FAB needed
AppDestination.Notes -> null
```

Also changed `onExitGraph` visibility from `private` to `protected` in all Navigators to allow preview subclasses to call `super`.

## Consequences

- Notes deep-links from Search now land on the correct note preview.
- All `@Preview` composables compile without composition-local crashes.
- `fabActionForNav3` is simpler and more correct.

## Links

- `NotesNavGraph.kt`, `NotesNavGraph.android.kt`, `NotesNavGraph.jvm.kt`
- `SearchScreen.kt` (preview section updated)
- `AccountSettingsScreen.kt` (preview section verified)
- `AndroidShellNav3.kt` (`fabActionForNav3`)
- `PreviewSearchNavigator`, `PreviewSettingsNavigator`
