---
status: accepted
date: 2026-09-25
---

# Note Templates and Daily Log Design

## Context

Lotti (Flutter) has two features missing from Singularity Todo:

1. **Daily Notes** — one note per day (identified by ISO date string `YYYY-MM-DD` as both identity anchor and display title), optionally seeded from a template.
2. **Note Templates** — any note can be saved as a template, then used to create new notes or daily notes.

The existing `NoteRepository` had no awareness of these concepts. Room schema had no `kind` column.

## Decision

### Schema: `kind` column on `notes`

```
ALTER TABLE notes ADD COLUMN kind TEXT DEFAULT 'Plain'
```

Three values: `Plain`, `Daily`, `Template`. Stored as enum string (matching the Room enum convention already used for `TaskKind`).

**Migration v21→v22** (`Migration21To22 : AutoMigrationSpec`): adds the column with default `'Plain'` so existing notes remain unaffected.

### Backup DTO

`NoteDto.kind: String = "Plain"` — serialized as plain string, deserialized via `NoteKind.valueOf(kind)`. Unknown values fall back gracefully (default `Plain` for old backups).

### Domain logic

Two pure classes (no DI, no Android dependencies):

- **`DailyNoteFactory`** — `dailyKey(date)`, `getOrCreate(dateKey, fromTemplateId)`, `prevDay(dateKey, minDate)`, `nextDay(dateKey, maxDate)`. Delegates to `NotesRepository`.
- **`TemplatePicker`** — `templates(): Flow<List<Note>>`, `apply(templateId, targetTitle, targetDateKey)`, `saveAsTemplate(id)`, `isTemplate(note)`.

### Repository interface additions

```kotlin
fun watchTemplates(): Flow<List<Note>>
fun watchDailyNotesInRange(from: String, to: String): Flow<List<Note>>
suspend fun getDailyNote(dateKey: String): Note?
suspend fun createFromTemplate(templateId: NoteId, targetTitle: String, targetDateKey: String?): Result<NoteId>
suspend fun saveAsTemplate(id: NoteId): Result<Unit>
suspend fun getOrCreateDailyNote(dateKey: String, fromTemplateId: NoteId?): Result<NoteId>
```

**`createFromTemplate`**: copies `bodyMarkdown`, `bodyHtml`, and `color` from the template. If `targetDateKey != null`, sets `kind = Daily` and title = `"$dateKey — $targetTitle"`. Otherwise `kind = Plain` and title = `targetTitle`.

**`getOrCreateDailyNote`**: checks for existing daily note first; creates from optional template if not found. Idempotent.

### UI changes

- **`NotesListState`** gains `templates: List<Note>` and `dailyNotes: List<Note>`.
- **`NotesListViewModel`** uses `combine()` over three flows: filtered notes + templates + current-month daily notes.
- **`NotesListScreen`**: renders "Daily Notes" and "Templates" sections above pinned/all-notes, each with a sticky header.
- Toolbar action on `NoteEditorScreen` is left as a follow-up (Phase F can add the template picker sheet alongside the AI actions).

## Rationale

- `LocalDate`/`DateTimeFormatter` in domain logic is pure Kotlin — no platform dependencies, fully testable in `commonTest`.
- Templates and daily notes share the same `Note` entity — no separate table or complex schema.
- Daily note identity is anchored to the ISO date string title — avoids needing a separate `date` column and keeps queries simple (`WHERE kind = 'Daily' AND title = ?`).
- `createFromTemplate` returns `Result<NoteId>` (not the full `Note`) to match the use pattern: caller navigates to the new note by ID.

## Consequences

- All existing `NoteEntity` construction sites (`createWithContent`, `createNoteWithTitle`) updated to pass explicit `kind = NoteKind.Plain`.
- `FakeNotesRepository` and `FakeNoteDao` updated with all 6 new methods for test coverage.
- Existing tests for note features verified passing with the new schema.

## Links

- Migration: `shared/src/commonMain/kotlin/com/singularity/todo/core/database/Migration21To22.kt`
- Domain: `DailyNoteFactory.kt`, `TemplatePicker.kt`
- Repository: `RoomNotesRepository.kt` (new methods, lines ~285–375)
