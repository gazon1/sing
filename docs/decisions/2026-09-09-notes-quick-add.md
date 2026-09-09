---
title: "Notes — quick-add inline input on the notes list screen"
date: 2026-09-09
tags: [notes, ux, quick-add]
---

## Context

Creating a new note required tapping the FAB, waiting for `NoteEditorScreen` to load, then typing the title. This is slower than necessary for a common operation.

## Decision

Add an inline `OutlinedTextField` below the filter chips row on `NotesScreen`. It appears immediately without any navigation.

**`QuickAddRow`** (`NotesScreen.kt`):
- `OutlinedTextField` with "Quick add note..." placeholder
- Leading `+` icon
- `ImeAction.Done` keyboard action: on submit, calls `onCreateNote(title)` and clears the field

**`NotesListViewModel.createNoteWithTitle(title: String): String`**:
- Generates a new `NoteId` using `IdGenerator`
- Calls `repo.createNoteWithTitle(userId, title)` — creates the DB row with title pre-filled
- Returns the new note id so the caller can navigate to the editor

**`NotesRepository.createNoteWithTitle(userId, title)`**:
- New method that creates a note with only the title populated
- Returns `Result<NoteId>`

**Navigation flow:**
`QuickAddRow` → `NotesScreenContent.onCreateNote(title)` → `NotesScreen.onCreateNote { viewModel.createNoteWithTitle(it) }` → `AppNavHost.NotesRoute.onNavigateToNote(id)` → `NoteEditor(noteId)`

## Consequences

### Positive
- One tap fewer than before for the common "capture a thought" workflow
- No loading screen — the text field is always visible in the list
- Title pre-saved to DB before navigating to editor (no lost titles on crash)

### Negative
- Slight visual complexity added to the list screen
- `NotesListViewModel` now requires `IdGenerator` as a third constructor parameter

## Links

- `NotesScreen.kt` — `QuickAddRow` composable
- `NotesListViewModel.kt` — `createNoteWithTitle` method
- `NotesRepository.kt` — `createNoteWithTitle` interface method
- `NotesDiModule.kt` — updated `NotesListViewModel` constructor
