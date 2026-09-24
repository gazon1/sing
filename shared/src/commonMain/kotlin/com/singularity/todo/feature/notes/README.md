# Notes

Rich-text WYSIWYG notes with Markdown export to HTML. Notes share the task domain's ID infrastructure and can link to tasks.

## Structure

```
notes/
├── EditorAction.kt     Sealed intent for note editor
├── EditorSession.kt    Editor session state machine
├── LinkSchemes.kt      Task/note URL scheme handling
├── NotesRepository.kt Repository interface
├── presentation/
│   ├── screen/  NotesListScreen, NoteEditorScreen, NotePreviewScreen
│   ├── viewmodel/ NotesListViewModel, NoteEditorViewModel
│   └── components/ Rich text toolbar, note rows
└── domain/      Note validation, link extraction
```

## Key entry points

| What | Where |
|---|---|
| Notes list | `NotesListScreen` + `NotesListViewModel` |
| Note editor | `NoteEditorScreen` + `NoteEditorViewModel` |
| Rich text | `EditorSession`, `LinkSchemes.kt` |
| Repository | `NotesRepository` (interface), `NotesRepositoryImpl` (Room) |

## AI tools using this domain

`create_note`, `update_note`, `delete_note`, `get_note`, `improve_note`, `cluster_notes`

## Relevant ADRs

- `docs/decisions/2026-09-05-rich-text-editor.md` — WYSIWYG architecture
- `docs/decisions/2026-09-05-koin-suspend-bridge.md`
- `docs/decisions/2026-09-22-explicit-overload-removal.md` — OutgoingLinksExtractor refactor
