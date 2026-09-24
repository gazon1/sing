---
title: "Nav3 Notes Navigator — Eliminate callback-passing in notes screens"
status: accepted
---
# Nav3 Notes Navigator — Eliminate callback-passing in notes screens

## Context

`NotesScreen`, `NotePreviewScreen`, and `NoteEditorScreen` received 4–6 manual
callback parameters each (`onBack`, `onEdit`, `onNavigateToNote`, `onNavigateToTask`,
`onNavigateToCreateNote`, `onDelete`). Adding a new cross-feature navigation
action required updating every call site and every preview. The `NoteCardActions`
and `NotesActions` `@JvmInline value class` wrappers partially hid the problem but
still required every screen to accept and forward callbacks.

Cross-feature navigation (opening a task from within the notes graph) was handled by
passing `onNavigateToTask` all the way down to `EditorTitleAndBody` — an internal
composable that has no business receiving app-level navigation.

The `AppDestination.NoteView` and `AppDestination.NoteEditor` outer routes were
deleted; `AppDestination.Notes` became the single entry point for the entire notes
feature.

## Decision

### Architecture

Each feature gets its own **nested Nav3 graph** with its own `NavBackStack<NotesRoute>`,
independent of the outer app back stack. Screens access navigation via
`LocalNotesNavigator.current` instead of receiving callbacks.

```
AppDestination.Notes (outer)
  └── NotesNavGraph (nested)
        ├── NavBackStack<NotesRoute>
        │     ├── NotesRoute.List         → NotesListScreen
        │     ├── NotesRoute.Preview(id) → NotePreviewScreen
        │     └── NotesRoute.Editor(id)  → NoteEditorScreen
        └── LocalNotesNavigator (CompositionLocal)
```

`NotesNavGraph` is an `expect fun` with platform-specific `actual` implementations:
- **Android**: includes `BackHandler` for system-back gesture and
  `rememberViewModelStoreNavEntryDecorator()` for per-entry VM scoping
- **JVM**: no decorators needed (desktop has no process-death or ComponentActivity
  scoping issues)

### Routes

```kotlin
sealed interface NotesRoute : NavKey {
    data object List : NotesRoute
    data class Preview(val noteId: NoteId) : NotesRoute
    data class Editor(val noteId: NoteId? = null) : NotesRoute
}
```

### Navigator

```kotlin
val LocalNotesNavigator = compositionLocalOf<NotesNavigator> {
    error("NotesNavigator not provided — wrap with NotesNavGraph")
}

open class NotesNavigator(
    private val backStack: NavBackStack<NotesRoute>,
    private val onExitGraph: (AppDestination?) -> Unit,
) {
    open fun openPreview(noteId: NoteId) { ... }
    open fun openEditor(noteId: NoteId?) { ... }
    open fun openTask(taskId: TaskId) { onExitGraph(TasksGraph(Inbox)) }
    open fun back() { ... }  // exits graph if size <= 1
    open fun closeGraph() { onExitGraph(null) }
}
```

### Cross-feature navigation

`NotesNavigator.openTask(taskId)` calls `onExitGraph(AppDestination.TasksGraph(Inbox))`,
exiting the notes nested graph and navigating to the tasks graph from the inbox.
This is implemented directly in the navigator — no callback-passing needed.

### Deleted types

- `AppDestination.NoteView` and `AppDestination.NoteEditor` — removed from outer graph
- `NoteCardActions` — deleted (swipe actions moved to `SwipeableNoteCard` which
  takes `navigator: NotesNavigator` directly)
- `NotesActions.Action.NavigateToNote` — removed from `NotesActions`
- `NotesActions.Action.CreateNote` — removed (UI calls `onCreateNote` callback directly)

### FAB fix

`AndroidShellNav3.fabActionForNav3` now receives `navigator: Navigator` as a
parameter (not closing over the composable scope) and wires:
`AppDestination.Notes -> FabAction("Add note") { navigator.navigate(AppDestination.Notes) }`.

### Public types moved to `feature.notes` root

`EditorState`, `NoteAiResult`, `NoteFilter`, `NoteSortOrder`, `NotesListState`,
and `NotesUiState` are defined in `feature.notes.Ids.kt` (not in
`presentation.viewmodel`) so they are accessible to `EditorSession.kt`,
`NoteFormatters.kt`, and `ContentStateMapper.kt` without cross-module dependencies.

## Rationale

The tasks feature already used this pattern (Nav3 + LocalNavigator), providing a
proven template. The `CompositionLocal` approach means:
- Screens are pure functions of route + VM state
- Navigation logic lives in one place (`NotesNavigator`)
- Adding a new cross-feature destination only changes `NotesNavigator`
- Previews use `PreviewNotesNavigator` (no-op) wrapped in `NotesPreviewWrapper`

`onExitGraph(AppDestination.TasksGraph(Inbox))` for `openTask` was chosen over
`openTask(taskId)` returning `AppDestination` because it keeps navigation
decisions encapsulated in the navigator.

## Consequences

- All notes screens now navigationally self-contained
- `NotesNavGraph(navCallbacks)` is the single integration point with the outer graph
- `NoteEditorScreen` still accepts `onNavigateToNote` and `onNavigateToTask` for
  `EditorTitleAndBody` (internal composable that renders rich-text links); these are
  provided by the screen-level wrapper using `navigator.openPreview/openTask`
- Pre-existing test failures (`RussianDateFormatterTest`, `TaskCreateViewModelTest`,
  `AppSmokeTest`) are unrelated to this migration

## Links

- `docs/decisions/2026-09-05-android-bottom-nav.md` — Nav3 architecture overview
- `docs/decisions/DIGEST.md` — decision index
