---
title: "AgendaEngine MR3 — Saved Views UI: routes, reducer, events, top-bar entry"
date: 2026-09-16
tags: [agenda, navigation3, reducers, events, koin]
---

## Context

MR2c deferred the Saved Views UI (list + edit screens) to MR3. MR3 must deliver a working top-bar entry point (IconButton → list → edit) in both Android and JVM builds, with read-only preview in the edit screen (create/deep-link deferred to MR4).

Key architectural questions that arose:
1. **Routing events**: Should `ViewSelected(id)` and `CreateNew` be VM `SharedFlow` events, or screen-side callbacks?
2. **Edit state**: Should `_draftName` be a separate `MutableStateFlow` mirror, or live inside `Editing` state?
3. **Top-bar entry**: Should the IconButton live in `AgendaScreen.kt` (Koin wrapper) or `AgendaContent.kt` (content)?
4. **`extraBufferCapacity`**: What value for `MutableSharedFlow` in ViewModels?

## Decisions

### 1. Routing belongs on screen — no `NavigateToEdit`/`NavigateToCreate` events

`ViewSelected(id)` from a list is a routing action, not a domain event. It belongs on the screen side:

```kotlin
// In SavedAgendaListScreen — screen-side navigation callback
onViewSelected = { viewId -> navigator.openSavedAgendaEdit(viewId) }
```

The VM only emits `ShowError` (for delete failures). `SaveSuccess`/`DeleteSuccess` → `NotificationHost` → `onNavigateBack`.

**Rule**: `MutableSharedFlow(extraBufferCapacity = 4)` for domain/stateful events only (errors, confirmations). Routing callbacks → screen.

### 2. `Editing` state carries `editableName`, `sectionCount`, `isSaving` directly

No separate `_draftName: MutableStateFlow`. The state shape:

```kotlin
data class Editing(
    val view: SavedAgendaView,
    val editableName: String,
    val sectionCount: Int?,
    val isSaving: Boolean = false,
) : SavedAgendaEditState {
    val canSave: Boolean
        get() = !isSaving && editableName.isNotBlank() && editableName != view.name
}
```

Draft seeding via lazy init inside `combine` — on first `repoFlow` emission, `_draftName.value = view.name` is set before constructing `Editing`.

**Rule**: Single source of truth for draft state. No mirror-state anti-pattern.

### 3. IconButton in `AgendaScreen.kt` (Koin wrapper), `onSavedViewsClick` passed to `AgendaContent`

```kotlin
// AgendaScreen.kt — Koin wrapper, has access to navigator
@Composable
fun AgendaScreen(...) {
    val navigator = LocalAgendaNavigator.current
    AgendaContent(
        state = state, title = vm.title, onIntent = vm::onIntent,
        onSavedViewsClick = { navigator.openSavedAgendaList() },
        ...
    )
}

// AgendaContent.kt — content-only, no navigator dependency
@Composable
fun AgendaContent(..., onSavedViewsClick: (() -> Unit)? = null, ...) {
    if (onSavedViewsClick != null) {
        IconButton(onClick = onSavedViewsClick) { Icon(Icons.Default.Bookmark, ...) }
    }
}
```

**Rule**: Keep content screens VM-as-param (preview-friendly). Navigator-dependent wiring → wrapper.

### 4. `extraBufferCapacity = 4` on `MutableSharedFlow` for domain events

`MutableSharedFlow<SavedAgendaListEvent>(extraBufferCapacity = 4)` — buffers 4 missed events (e.g. rapid delete taps). `WhileSubscribed(5_000)` on `stateIn` prevents upstream gap.

## Consequences

- **MR4 scope**: Create flow (FAB on list), deep-link guard for `SavedAgendaEdit`, section reorder.
- **Fake reactive** (`MutableStateFlow<Map<K,V>>`) required for VM tests — `flowOf(snapshot)` not testable for transitions.
- `agendaEntryProvider()` on both platforms must register `SavedAgendaList` and `SavedAgendaEdit` entries.
- `navSavedStateConfig` on Android must include `SavedAgendaList.serializer()` and `SavedAgendaEdit.serializer()`.

## Links

- `feature/agenda/presentation/screen/SavedAgendaListScreen.kt`
- `feature/agenda/presentation/screen/SavedAgendaEditScreen.kt`
- `feature/agenda/presentation/viewmodel/SavedAgendaListViewModel.kt`
- `feature/agenda/presentation/viewmodel/SavedAgendaEditViewModel.kt`
- `feature/nav/AgendaStartRoute.kt` — `SavedAgendaList`, `SavedAgendaEdit(viewId: String)`
- `AgendaNavGraph.android.kt`, `AgendaNavGraph.jvm.kt` — entry registration
- `docs/decisions/2026-09-06-koin-vm-viewmodelof-koinviewmodel.md` — VM DI scope rules
