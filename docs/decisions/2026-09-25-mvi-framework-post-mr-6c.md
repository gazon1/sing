---
status: accepted
date: 2026-09-25
deciders: Singularity Developer
---

# Post-MR-6c Audit: MVI Framework Migration

## Context

After completing MR-6c (SearchViewModel + ProjectsViewModel migration) and MR-6d-early (TOCTOU bug fixes for TaskDetailVM + ProjectDetailVM), this ADR records remaining issues and the next steps.

## What's Done ✅

### MR-6c: SearchViewModel + ProjectsViewModel Migration

- **SearchViewModel**: Replaced hand-rolled `_events MutableSharedFlow` + `_state MutableStateFlow` with MviViewModel base class. `processIntent` → `onIntent`. Screen updated to call `onIntent()`.
- **ProjectsViewModel**: Same pattern. Added `ProjectsIntent` sealed interface. Screen and tests updated to use `onIntent()`.
- **ProjectsViewModelTest**: Updated to use `onIntent` instead of direct method calls on the VM.

### MR-6d-early: TOCTOU Bug Fixes

**TaskDetailViewModel** (`TaskDetail.kt`):
- `mutate()` previously took `current: Task` as a parameter — a snapshot captured at intent dispatch time. If `_latestTask` changed between intent arrival and mutation execution, the wrong task version was persisted.
- Fix: `mutate()` now reads `_latestTask.value` **inside** `scope.launch`, ensuring fresh read at execution time.
- Also fixed: `SetDependencies`, `ToggleChecklistItem`, `AddChecklistItem` — these launched coroutines that referenced `current.id` from a stale capture. Now re-read `_latestTask.value` inside the launched scope.

**ProjectDetailViewModel** (`ProjectDetailViewModel.kt`):
- Same TOCTOU pattern. `mutate(current)` was capturing stale project snapshot.
- Fix: `mutate()` now reads `_latestProject.value` inside `fireAndForget` block.
- Init debouncers simplified — no longer need manual `val current = _latestProject.value ?: return@debounce`.

**Severity**: Medium — data corruption possible when user rapidly changes fields while upstream data is loading.

## Remaining Issues

### 1. TaskDetailViewModel — MutableSharedFlow for titleEdits/descriptionEdits

**File:** `feature/tasks/presentation/viewmodel/TaskDetail.kt:79-80`

```kotlin
private val titleEdits = MutableSharedFlow<String>(extraBufferCapacity = 4)
private val descriptionEdits = MutableSharedFlow<String>(extraBufferCapacity = 4)
```

These are **input channels** (user typing) — not output event streams. They are consumed by debounce collectors in init and are correctly NOT migrated to EventBus (EventBus is for one-shot UI events, not continuous input streams).

**Verdict**: Legitimate use of `MutableSharedFlow`. No fix needed.

### 2. BackupViewModel — 2× MutableSharedFlow + Screen API

**File:** `feature/backup/BackupViewModel.kt:57-61`

```kotlin
private val _events = MutableSharedFlow<BackupUiEvent>(extraBufferCapacity = 4)
private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 4)
val events: SharedFlow<BackupUiEvent> = _events.asSharedFlow()
val snackbar: SharedFlow<String> = _snackbar.asSharedFlow()
```

**Problem**: Screen (`BackupScreen`) subscribes to both flows separately. Migrating to MviViewModel requires:
1. Adding `ShowSnackbar(String)` to `BackupUiEvent`
2. Unifying snackbar into single event channel
3. Updating screen to handle snackbar via event mapping

**Severity**: Medium — requires screen API change.

**Status**: Not scheduled. BackupVM works correctly today.

### 3. SettingsViewModel — Intent not MviIntent

**File:** `feature/settings/SettingsViewModel.kt`

`SettingsIntent` is a typealias to `core.settings.SettingsIntent` which doesn't extend `MviIntent`. Making it extend `MviIntent` would require:
1. Changing core module sealed interface hierarchy
2. All nested sealed interfaces (Appearance, Notifications, etc.) to extend MviIntent

**Severity**: Medium (architectural change in core).

**Status**: Not scheduled.

## VM Migration Status (Post MR-6c)

| VM | Status | Notes |
|---|---|---|
| AgendaViewModel | ✅ MviViewModel | |
| ArchiveViewModel | ✅ MviViewModel | |
| AiUsageViewModel | ✅ MviViewModel | |
| AuthViewModel | ✅ MviViewModel | |
| CalendarViewModel | ✅ MviViewModel | |
| CalendarSyncViewModel | ✅ MviViewModel | |
| ChatViewModel | ✅ MviViewModel | |
| NoteEditor | ✅ DraftMviViewModel | |
| NotePreview | ✅ MviViewModel | |
| ProfileSwitcherViewModel | ✅ MviViewModel | |
| ProjectEditorViewModel | ✅ MviViewModel | |
| SavedAgendaListViewModel | ✅ MviViewModel | |
| SavedAgendaViewModel | ✅ MviViewModel | |
| SearchViewModel | ✅ MviViewModel | MR-6c |
| ProjectsViewModel | ✅ MviViewModel | MR-6c |
| SyncViewModel | ✅ MviViewModel | |
| TagsViewModel | ✅ MviViewModel | |
| TaskCreateViewModel | ✅ DraftMviViewModel | |
| TaskDetailViewModel | ⚠️ ViewModel | TOCTOU fixed; MutableSharedFlow for input channels |
| ProjectDetailViewModel | ⚠️ ViewModel | TOCTOU fixed; hand-rolled events |
| SettingsViewModel | ⚠️ ViewModel | Intent не MviIntent |
| BackupViewModel | ⚠️ ViewModel | 2× MutableSharedFlow + screen API |

**Total**: 17 ✅ on MviViewModel/DraftMviViewModel, 4 ⚠️ remaining.

## TOCTOU Bug Fix Summary

| VM | Bug | Fix | Status |
|---|---|---|---|
| TaskDetailVM | `mutate(current)` captured stale Task | `mutate()` re-reads `_latestTask.value` inside scope | ✅ Fixed |
| ProjectDetailVM | `mutate(current)` captured stale Project | `mutate()` re-reads `_latestProject.value` inside scope | ✅ Fixed |

## Next Steps

1. **MR-6d**: TaskDetailVM full migration to MviViewModel (input MutableSharedFlows stay as-is)
2. **MR-6d**: ProjectDetailVM full migration to MviViewModel
3. **TBD**: SettingsVM — requires core SettingsIntent hierarchy change
4. **TBD**: BackupVM — requires snackbar unification in screen API
