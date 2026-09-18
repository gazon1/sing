---
title: "Notes — split NoteDetail into NoteView (read-only) and NoteEditor (edit)"
date: 2026-09-09
tags: [notes, navigation, rich-editor, ux]
status: accepted
---

## Context

Before this decision, `AppDestination.NoteDetail` opened `NoteEditorScreen` in edit mode. There was no dedicated read-only view — the same editor was used for viewing and editing.

Problems:
- **UX confusion** — no way to open a note without being in edit mode
- **Backlinks UI missing** — backlinks (incoming links from other notes) were not shown at all
- **Title/metadata display** — editing UI prioritizes input fields; read-only display can show metadata (word count, last updated) more elegantly
- **Bottom bar actions** — editing toolbar and save/cancel actions are only relevant for editing; read-only needs edit/delete/backlinks actions

## Decision

Replace `AppDestination.NoteDetail` with two distinct destinations:

```
AppDestination.NoteView(val noteId: String)  // read-only — NotePreviewScreen
AppDestination.NoteEditor(val noteId: String? = null)  // edit — NoteEditorScreen
```

**Navigation flow:**
- Tapping a note card in `NotesScreen` → `NoteView(noteId)`
- In `NoteView`: "Edit" button → `NoteEditor(noteId)` → on save → back to `NoteView`
- In `NoteView`: "Back" → `NotesScreen`
- FAB in `NotesScreen` → `NoteEditor(null)` (create new) → on save → `NoteView(createdId)`

**NotePreviewScreen** (read-only view):
- TopAppBar: back, backlinks button (badge count), overflow menu
- Hero section: large title, relative timestamp, word count chip
- Body: `RichText(state)` composable (NOT `RichTextEditor`) — read-only rich text
- `LocalUriHandler` for tapping `note://` and `task://` links
- BottomAppBar: FilledTonalButton "Edit", OutlinedButton "Backlinks" (count), OutlinedButton "Delete"
- BacklinksSheet: list of notes linking TO this note, with snippet extraction

**NoteEditorScreen** (edit):
- Uses `RichTextEditor` (editable)
- `EditorSession` holds `RichTextState` shared between toolbar and body
- Autosave via `AutosaveScheduler`
- AI improve via `ImproveNoteUseCase`
- No backlinks UI (delegated to `NotePreview`)

## Rationale

- **Two distinct use cases deserve two distinct screens** — read vs write have different UI needs
- **`RichText` (read-only) vs `RichTextEditor`** — read-only composable has no cursor, selection, or keyboard focus, making it cleaner for viewing
- **Backlinks are read-only metadata** — they belong in `NotePreview`, not in the editing flow
- **Route symmetry with Tasks** — `TaskDetail` and `TaskEditor` are already separate; Notes now follow the same pattern
- **Prevents accidental edits** — navigating to a note no longer puts it in edit mode

## Consequences

### Positive
- Clear UX: notes list → tap note → read → optionally edit
- Backlinks are now shown and functional
- Note metadata (word count, last updated) is visible without entering edit mode
- Delete confirmation is handled in `NotePreview`, not buried in editor overflow menu

### Negative
- Navigation now has one more route: `NoteView` ↔ `NoteEditor` ↔ `NotesScreen`
- User must explicitly tap "Edit" to modify — one additional tap for casual reading
- `NotePreview` must observe the note via `repo.watchNote()` — requires a Flow subscription

## Alternatives Considered

1. **Keep single screen with edit/read-only toggle** — rejected: mixed UI concerns, harder to test, poor UX
2. **NoteDetail with `isEditing: Boolean` parameter** — rejected: same problem, just parameterised
3. **Only `NoteEditor`, hide toolbar when not editing** — rejected: `RichTextEditor` still shows cursor/focus

## Links

- `AppDestination.kt` — `NoteView`, `NoteEditor` data classes
- `AppNavHost.kt` — composable routes
- `NotePreviewScreen.kt` — read-only view
- `NoteEditorScreen.kt` — edit screen
- `NotePreview.kt` — ViewModel
- `NoteEditor.kt` — ViewModel
