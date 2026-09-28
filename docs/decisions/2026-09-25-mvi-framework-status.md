---
title: MVI Framework Audit Summary (Post MR-6a/6b/7)
date: 2026-09-25
status: accepted
deciders: Singularity Developer
deciders: Singularity Developer
---

> **Superseded in part (2026-09-28):** this ADR's "6 ⚠️ remaining" and the
> `SettingsViewModel` / `BackupViewModel` / `SearchViewModel` / `ProjectsViewModel` rows
> below are historical. A census during roadmap MR-1 found **every** production ViewModel
> — all 24 — already on `MviViewModel` or `DraftMviViewModel`, with zero bare
> `androidx.lifecycle.ViewModel` subclasses. `BackupViewModel`, `SettingsViewModel`,
> `SearchViewModel` and `ProjectsViewModel` were migrated by
> `2026-09-27-mvi-single-state-entry-and-vm-sweep`.
>
> The TOCTOU rows (2 and 3) are resolved as follows. `TaskDetailViewModel` was deleted by
> the slot refactor. `ProjectDetailViewModel`'s `_latestProject` cache turned out to be
> write-only — the two comments claiming `mutate` re-read it were false — so there was no
> TOCTOU to fix; the cache is gone. See
> `2026-09-28-mr2-project-detail-retro.md`.
>
> Still open from this ADR: item 5, `SettingsIntent` not being an `MviIntent`; and the
> `SettingsContributor` → `FeatureSlot` conversion, which that same sweep deliberately
> deferred. See `2026-09-28-roadmap-status.md`.

# MVI Framework Audit Summary (Post MR-6a/6b/7)

## Context

After completing MR-6a, MR-6b, and MR-7, this ADR summarizes the final state of the MVI framework consolidation and remaining technical debt.

## Critical Bugs — All Fixed ✅

| Bug | File | Fix | Commit |
|---|---|---|---|
| `SearchViewModel.onTogglePin` crash (`throw IllegalStateException` in `fireAndForget`) | `SearchViewModel.kt:344` | Added `TaskRepository` dependency + `taskRepo.togglePinned()` | `4232858c` (MR-6a) |
| `NoteEditor.save()` bypassed `persist()` | `NoteEditor.kt` | Removed override, rely on framework `persist()` | `4e591f58` |
| `NoteEditor` autosave overwrote `createdAt` | `NoteEditor.kt` | Cache existing note, preserve `createdAt` | `4e591f58` |
| Dead `pendingAutosaveJob` in `DraftMviViewModel` | `DraftMviViewModel.kt` | Removed dead code | `4e591f58` |
| `ProfileSwitcherVM._errorMessage` referenced before declaration | `ProfileSwitcherVM.kt` | Moved before `init` | `dd1a6c3e` (MR-6a) |

## Framework Improvements ✅

| Change | File | Commit |
|---|---|---|
| `MviViewModel.vmScope` made `protected open` | `MviViewModel.kt` | `dd1a6c3e` |
| `StatefulViewModel.setState` made `open` | `StatefulViewModel.kt` | `dd1a6c3e` |
| `MviViewModel.setState` override added | `MviViewModel.kt` | `dd1a6c3e` |
| `StateFlowExt.kt` deleted (no longer needed) | `StateFlowExt.kt` | `28b2b322` (MR-7) |

## Remaining Technical Debt

### 1. SearchViewModel — MutableSharedFlow + MutableStateFlow

**File:** `feature/search/SearchViewModel.kt:130,133`

```kotlin
private val _events = MutableSharedFlow<SearchUiEvent>(extraBufferCapacity = 4)
private val _state = MutableStateFlow(SearchUiState())
```

**Status:** Planned for MR-6c. OnTogglePin crash is fixed. Remaining: migrate to MviViewModel.

### 2. TaskDetailViewModel._latestTask TOCTOU

**File:** `feature/tasks/presentation/viewmodel/TaskDetail.kt:211`

```kotlin
fun onIntent(intent: TaskDetailIntent.Domain) {
    val current = _latestTask.value ?: return  // TOCTOU
    when (intent) {
        // uses `current` — if _latestTask.value changes, stale data used
    }
}
```

**Severity:** Low. User clicks are serialized; rarely manifests.

**Status:** Planned for MR-6d.

### 3. ProjectDetailViewModel._latestProject TOCTOU (same pattern)

**File:** `feature/projects/presentation/viewmodel/ProjectDetailViewModel.kt:236,241,246,251,257`

Same TOCTOU pattern as TaskDetailVM — `val current = _latestProject.value ?: return`.

**Severity:** Low.

**Status:** Same MR-6d or combined with TaskDetailVM fix.

### 4. BackupViewModel — 2× MutableSharedFlow + screen API

**File:** `feature/backup/BackupViewModel.kt:57,60`

```kotlin
private val _events = MutableSharedFlow<BackupUiEvent>(...)
private val _snackbar = MutableSharedFlow<String>(...)
```

**Problem:** Screen (`BackupScreen`) subscribes to both flows separately. Migrating to MviViewModel requires:
1. Adding `ShowSnackbar(String)` to `BackupUiEvent`
2. Unifying snackbar into single event channel
3. Updating screen to handle snackbar via event mapping

**Severity:** Medium (requires screen API change).

**Status:** Not yet scheduled.

### 5. SettingsViewModel — Intent not MviIntent

**File:** `feature/settings/SettingsViewModel.kt`, `core/settings/SettingsBundle.kt`

`SettingsIntent` is a typealias to `core.settings.SettingsIntent` which doesn't extend `MviIntent`. Making it extend `MviIntent` would require:
1. Changing core module sealed interface hierarchy
2. All nested sealed interfaces (Appearance, Notifications, etc.) to extend MviIntent

**Severity:** Medium (architectural change in core).

**Status:** Not yet scheduled.

### 6. ProjectsViewModel — not on MviViewModel

**File:** `feature/projects/presentation/viewmodel/ProjectsViewModel.kt`

Uses hand-rolled `ViewModel()` + `MutableStateFlow` pattern.

**Severity:** Low (working code).

**Status:** Not yet scheduled.

## Verified Clean ✅

- ❌ `throw` in VMs: only in domain/use cases (legitimate)
- ❌ StateFlowExt: deleted
- ❌ NoteSaver: deleted
- ❌ Duplicate ChatUiEvent.kt: deleted
- ❌ CalendarViewModel copy(): uses private `CalendarState` data class
- ❌ fireAndForget crashes: all have `onError` handlers
- ❌ MutableSharedFlow crashes: only in BackupVM (with onError) and SearchVM (fixed)

## VM Migration Status

| VM | Status | Notes |
|---|---|---|
| AgendaViewModel | ✅ MviViewModel | |
| ArchiveViewModel | ✅ MviViewModel | |
| AiUsageViewModel | ✅ MviViewModel | |
| AuthViewModel | ✅ MviViewModel | |
| CalendarViewModel | ✅ MviViewModel | Migrated MR-6a |
| CalendarSyncViewModel | ✅ MviViewModel | |
| ChatViewModel | ✅ MviViewModel | Migrated MR-6a |
| NoteEditor | ✅ DraftMviViewModel | |
| NotePreview | ✅ MviViewModel | |
| ProfileSwitcherViewModel | ✅ MviViewModel | |
| ProjectEditorViewModel | ✅ MviViewModel | |
| SavedAgendaListViewModel | ✅ MviViewModel | |
| SavedAgendaViewModel | ✅ MviViewModel | |
| SettingsViewModel | ⚠️ ViewModel | Intent не MviIntent |
| SyncViewModel | ✅ MviViewModel | |
| TagsViewModel | ✅ MviViewModel | |
| TaskCreateViewModel | ✅ DraftMviViewModel | |
| **BackupViewModel** | ⚠️ ViewModel | MutableSharedFlow + screen API |
| **SearchViewModel** | ⚠️ ViewModel | MutableSharedFlow, planned MR-6c |
| **ProjectsViewModel** | ⚠️ ViewModel | Hand-rolled |
| **ProjectDetailViewModel** | ⚠️ ViewModel | TOCTOU bug |
| **TaskDetailViewModel** | ⚠️ ViewModel | TOCTOU bug, planned MR-6d |

**Total:** 15 ✅ on MviViewModel/DraftMviViewModel, 6 ⚠️ remaining.

## Actions

| MR | VMs | Main Tasks |
|---|---|---|
| MR-6c | SearchVM, ProjectsVM | MutableSharedFlow → EventBus, ProjectsVM migration |
| MR-6d | TaskDetailVM, ProjectDetailVM | TOCTOU fix, TaskDetailDraftState unification |
| TBD | SettingsVM | Core SettingsIntent → MviIntent |
| TBD | BackupVM | Snackbar unification + MviViewModel |

## Consequences

- All critical bugs are fixed
- 15/21 VMs are on the MVI framework
- Framework API is stable (vmScope open, setState overridable)
- StateFlowExt deleted — no deprecated API remaining
- Remaining VMs are either: (a) complex migrations requiring screen API changes, (b) core module changes, or (c) planned for later MRs
