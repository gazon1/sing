---
status: accepted
date: 2026-09-25
deciders: Singularity Developer
---

# Post-MR-6d Audit: MVI Framework Migration — Final Status

## Context

After completing MR-6c (SearchVM + ProjectsVM), MR-6d-early (TOCTOU fixes), and MR-6d (full TaskDetailVM + ProjectDetailVM migration), this ADR records the final migration status.

## Migration Complete ✅

**19 of 21 VMs** are now on `MviViewModel` or `DraftMviViewModel`. Framework is stable.

### MR-6c: SearchVM + ProjectsVM

- **SearchViewModel**: `_events MutableSharedFlow` + `_state MutableStateFlow` → MviViewModel. `processIntent` → `onIntent`. Screen updated.
- **ProjectsViewModel**: Same pattern. Added `ProjectsIntent` sealed interface. Screen + tests updated.
- Both compile, tests pass.

### MR-6d: TaskDetailVM + ProjectDetailVM (full migration)

**TaskDetailVM** (`TaskDetail.kt`):
- `ViewModel()` → `MviViewModel<TaskDetailUiState, TaskDetailIntent.Domain, TaskDetailUiEvent>`
- `TaskDetailIntent` extends `MviIntent`, `TaskDetailUiEvent` extends `MviEvent`
- Removed `_events Channel`, `_state MutableStateFlow` — uses inherited `state` and `events` from MviViewModel
- All `scope.launch` → `vmScope.launch`
- All `_state.value = X` → `setState(X)`
- All `_events.trySend(X)` → `emit(X)`
- `onIntent` → `override fun onIntent`
- **TOCTOU fully fixed**: every launched coroutine re-reads `_latestTask.value` inside the coroutine body

**ProjectDetailVM** (`ProjectDetailViewModel.kt`):
- Same pattern: `MviViewModel<ProjectDetailUiState, ProjectDetailIntent.Domain, ProjectDetailUiEvent>`
- Removed `_events Channel`, `state` property — uses inherited `state` and `events`
- All `scope.launch/fireAndForget` → `vmScope.launch/fireAndForget`
- All `_events.trySend` → `emit`
- `onIntent` → `override fun onIntent`

## Remaining VMs (2)

### 1. SettingsViewModel — Intent not MviIntent

**File:** `feature/settings/SettingsViewModel.kt`

`SettingsIntent` is a typealias to `core.settings.SettingsIntent` which doesn't extend `MviIntent`. Making it extend `MviIntent` requires changing the core module sealed interface hierarchy — all nested sealed interfaces (Appearance, Notifications, etc.) would need to extend `MviIntent`.

**Severity**: Medium (architectural change in core).

**Status**: Not scheduled. Works correctly today.

### 2. BackupViewModel — 2× MutableSharedFlow + Screen API

**File:** `feature/backup/BackupViewModel.kt:57-61`

```kotlin
private val _events = MutableSharedFlow<BackupUiEvent>(extraBufferCapacity = 4)
private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 4)
```

Screen (`BackupScreen`) subscribes to both flows separately. Migrating to MviViewModel requires:
1. Adding `ShowSnackbar(String)` to `BackupUiEvent`
2. Unifying snackbar into single event channel
3. Updating screen to handle snackbar via event mapping

**Severity**: Medium (requires screen API change).

**Status**: Not scheduled. Works correctly today.

## VM Migration Status (Final)

| VM | Status | Notes |
|---|---|---|
| AgendaViewModel | ✅ MviViewModel | |
| ArchiveViewModel | ✅ MviViewModel | |
| AiUsageViewModel | ✅ MviViewModel | |
| AuthViewModel | ✅ MviViewModel | |
| CalendarViewModel | ✅ MviViewModel | MR-6a |
| CalendarSyncViewModel | ✅ MviViewModel | |
| ChatViewModel | ✅ MviViewModel | MR-6a |
| NoteEditor | ✅ DraftMviViewModel | |
| NotePreview | ✅ MviViewModel | |
| ProfileSwitcherViewModel | ✅ MviViewModel | MR-6a |
| ProjectEditorViewModel | ✅ MviViewModel | |
| ProjectDetailViewModel | ✅ MviViewModel | MR-6d |
| ProjectsViewModel | ✅ MviViewModel | MR-6c |
| SavedAgendaListViewModel | ✅ MviViewModel | |
| SavedAgendaViewModel | ✅ MviViewModel | |
| SearchViewModel | ✅ MviViewModel | MR-6c |
| SyncViewModel | ✅ MviViewModel | |
| TagsViewModel | ✅ MviViewModel | |
| TaskCreateViewModel | ✅ DraftMviViewModel | |
| TaskDetailViewModel | ✅ MviViewModel | MR-6d |
| **BackupViewModel** | ⚠️ ViewModel | 2× MutableSharedFlow |
| **SettingsViewModel** | ⚠️ ViewModel | Intent не MviIntent |

**Total: 19 ✅ on MviViewModel/DraftMviViewModel, 2 ⚠️ remaining.**

## Critical Bugs Fixed

| Bug | File | Fix | Commit |
|---|---|---|---|
| TaskDetailVM TOCTOU (stale `current` capture) | `TaskDetail.kt` | All handlers re-read `_latestTask.value` inside launched coroutines | `8b4df300` |
| ProjectDetailVM TOCTOU (stale `current` capture) | `ProjectDetailViewModel.kt` | `mutate()` reads `_latestProject.value` inside `fireAndForget` | `61782744` |
| SearchVM `onTogglePin` crash | `SearchViewModel.kt` | Added `taskRepo.togglePinned()` | `4232858c` |
| NoteEditor `save()` bypassed `persist()` | `NoteEditor.kt` | Removed override, rely on framework `persist()` | `4e591f58` |
| NoteEditor autosave overwrote `createdAt` | `NoteEditor.kt` | Cache existing note, preserve `createdAt` | `4e591f58` |
| Dead `pendingAutosaveJob` in DraftMviViewModel | `DraftMviViewModel.kt` | Removed dead code | `4e591f58` |
| ProfileSwitcherVM `_errorMessage` ref before declaration | `ProfileSwitcherVM.kt` | Moved before `init` | `dd1a6c3e` |

## Verified Legitimate Patterns (No Fix Needed)

- **TaskDetailVM `titleEdits`/`descriptionEdits`**: Input channels (user typing → debounce → save), not output event streams. `MutableSharedFlow` is correct.
- **`StateFlowExt.kt`**: Deleted (MR-7).
- **`NoteSaver.kt`**: Deleted (MR-6.0).
- **`fireAndForget` crashes**: All have `onError` handlers.

## Framework API (Stable)

- `MviViewModel.vmScope`: `protected open` — subclasses can `override`
- `MviViewModel.setState`: `protected open` — subclasses can `override`
- `MviViewModel.updateState(transform: (S) → S)`: reducer-style mutations
- `MviViewModel.updateStateAs<T>(transform: (T) → S)`: type-safe for sealed hierarchies
- `MviViewModel.emit(event: E)`: suspend fun for one-shot events via EventBus
- `MviViewModel.tryEmit(event: E)`: non-suspending fallback

## Next Steps

| Priority | Action | Notes |
|---|---|---|
| Low | MR-6d-append: BackupVM migration | Requires snackbar unification in screen API |
| Low | MR-6d-append: SettingsVM migration | Requires core SettingsIntent → MviIntent hierarchy change |
| Low | MR-8: Side effects + state history framework | `launchExclusive`, `launchResult`, `StateHistory` ring buffer |
