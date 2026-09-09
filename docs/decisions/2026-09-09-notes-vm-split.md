---
title: "Notes — split god-class NotesViewModel into 3 focused ViewModels"
date: 2026-09-09
tags: [notes, architecture, viewmodel, di]
---

## Context

`NotesViewModel` (373 lines) was a "god class" handling five completely different lifecycle scopes:

1. **List scope** — filtering, sorting, multi-select, pin, archive, swipe-to-dismiss
2. **Editor scope** — open/create/close editor, title+body edit, autosave, AI improve, link picker
3. **Preview scope** — observe single note, backlinks, delete navigation

These scopes never overlap. A single note being edited has no effect on the list filter. The preview never touches editor state.

Keeping them in one ViewModel meant:
- Every state holder (`filter`, `selectedIds`, `editorState`, `previewNote`) was alive for the entire app lifetime
- Testing required instantiating all five concerns even when testing only one
- Confusing naming (`NotesViewModel.openEditor`, `NotesViewModel.navigateToNote`)

## Decision

Split into three focused ViewModels, each with a single clear responsibility:

| ViewModel | Lines | Responsibility |
|---|---|---|
| `NotesListViewModel` | ~120 | List: filter, sort, multi-select, pin, archive, swipe |
| `NoteEditor` | ~150 | Editor session: open/create/close, title+body edit, autosave, AI |
| `NotePreview` | ~60 | Read-only view: observe note, backlinks, delete |

**Key architectural decisions per VM:**

### NotesListViewModel
Injects `NotesRepository`, `ProfileAwareCurrentUser`. Owns `StateFlow<NotesUiState>` with sealed `Empty / Loading / Loaded`.

### NoteEditor
Injects `NotesRepository`, `ProfileAwareCurrentUser`, `IdGenerator`, `AutosaveScheduler`, optional `ImproveNoteUseCase`. Owns `StateFlow<EditorState>` (sealed: `Empty / Loading / Editing / Saving`). Extracts `persist(html, title, id, navigateBack)` private method to eliminate 6-line duplication between `saveNow` and `scheduleAutosave`.

### NotePreview
Injects `NotesRepository`, `InternalLinkRepository`, `ProfileAwareCurrentUser`. Owns `StateFlow<NotePreviewState>` (sealed: `Loading / Loaded(note, backlinks)`). Observes single note via `repo.watchNote()`, fetches backlinks via `linkRepo.getBacklinkNotes()`.

## Rationale

- **Single responsibility** — each VM has one reason to exist and one reason to change
- **Lifetime correctness** — editor and preview state are not allocated until their screens are opened
- **Testability** — unit tests only instantiate the VM under test; no false interactions from unrelated state
- **Named accurately** — `NotesListViewModel.list`, `NoteEditor.editorState`, `NotePreview.state` make the code self-documenting
- **DI alignment** — each VM gets its own `viewModel { }` registration in `NotesDiModule`, matching the Koin scope-per-VM pattern

## Consequences

### Positive
- VMs are independently testable with focused test suites
- Editor session state is released when user navigates away
- Each VM is small enough to understand fully (~60-150 lines)
- Previews for each screen can use `koinViewModel { parametersOf(...) }` without circular dependency

### Negative
- Three Koin registrations instead of one
- `NotesRoute` now injects `NotesListViewModel` via `koinViewModel()`, `NoteEditor` and `NotePreview` are injected via their respective screen composables
- Cross-screen state (e.g. "did the user just save a note") must flow through navigation callbacks, not shared VM state

## Alternatives Considered

1. **Keep single VM with clear internal separation** — rejected because StateFlow properties remain alive for app lifetime even if logically partitioned
2. **Use `ScopeManager` / coroutine scope per feature** — more complex, harder to test
3. **One VM per feature tab + ScreenScope** — over-engineering for this use case

## Links

- `NotesListViewModel.kt`, `NoteEditor.kt`, `NotePreview.kt`
- `NotesDiModule.kt` — DI registrations
- `NoteEditorScreen.kt`, `NotePreviewScreen.kt`, `NotesScreen.kt`
