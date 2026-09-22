---
status: accepted
date: 2026-09-23
---

# Dead currentUser and orphan VM cleanup

## Context

After PR 2 (repository ambient userId stamping), several VMs and UI state classes still carried dead or unused `ProfileAwareCurrentUser` injections and dead `userId` fields.

- `ProjectsViewModel`, `NotesListViewModel`, `ProjectEditorViewModel`, `ProjectDetailViewModel` all injected `ProfileAwareCurrentUser` but only `ProjectsViewModel` and `NotesListViewModel` actually used `userId` in state construction — and only in the now-obsolete `Empty(userId)` pattern.
- `ProjectsUiState.Empty(val userId: UserId)` and `NotesUiState.Empty(val uid: UserId)` — the `userId` field was passed to the UI state but never read by any Composable.
- `ChecklistEditorViewModel` — zero UI consumers, no navigation route, no screen. An orphan since the checklist feature was redesigned.

## Decision

### A. Drop `currentUser` from VMs where it is never read

Confirmed dead via code inspection:

| VM | `currentUser` param | `userId` property | Used? |
|---|---|---|---|
| `ProjectEditorViewModel` | ✅ | ✅ | **Never** — `userId` declared but zero call sites |
| `ProjectDetailViewModel` | ✅ | ✅ | **Never** — declared but never referenced |

Removed from: constructor params, `private val userId get() = ...` property, DI registrations (`ProjectsDiModule`), test constructors (`ProjectEditorViewModelTest`), and preview constructors (`ProjectEditorScreen.kt`).

### B. Drop dead `userId` from UI state `Empty` classes

Confirmed dead via Agent 2 — zero UI reads of `ProjectsUiState.Empty.userId` and `NotesUiState.Empty.uid`.

| State class | Field | Removed |
|---|---|---|
| `ProjectsUiState.Empty` | `val userId: UserId` | ✅ → `data object Empty` |
| `NotesUiState.Empty` | `val uid: UserId` | ✅ → `data object Empty` |

All call sites updated: `ProjectsViewModel`, `NotesListViewModel`, `ProjectsScreen.kt` preview, `NotesListScreen.kt` preview.

### C. Delete orphan `ChecklistEditorViewModel`

Confirmed orphan via Agent 3 — zero consumers, no nav route, no screen.

- Deleted `feature/checklist/ChecklistEditorViewModel.kt`
- Removed from `TasksDiModule.kt` (`viewModel { (taskId: String) -> ChecklistEditorViewModel(...) }`)
- Fixed stale KDoc in `ChecklistItemRow.kt` (removed reference to `ChecklistEditorSheet`)

## Rationale

**Why `data object Empty` instead of `data class Empty(val reason: String)` or similar**: The `Empty` state carries no data in any of the three screens (Projects, Notes, Agenda). Converting to `data object` eliminates the unused field entirely and simplifies pattern matching at call sites.

**Why fail-loud on orphan VM**: An empty shell that compiles but is never reachable is a maintenance hazard. Deletion makes the absence of the feature explicit.

## Consequences

- 4 VMs no longer inject `ProfileAwareCurrentUser`
- 2 UI state classes simplified (`data object` instead of `data class` with dead field)
- 1 orphan VM deleted
- 4 test files updated (removed `fakeCurrentUser` args where no longer needed)
- 2 screen preview functions updated
- detekt: 0 new findings | jvmTest: green

## Links

- PR 2: `b4ce40b` — repository ambient userId stamping
- Skill: `singularity-todo-clean-architecture-audit`
- Skill: `singularity-todo-testable-vm`
