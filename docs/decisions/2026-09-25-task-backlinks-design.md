---
status: accepted
date: 2026-09-25
---

# Task Backlinks — Design

## Context

Tasks can be linked from other tasks or notes using `task://<id>` URL scheme (Obsidian-style wikilinks). When viewing a task, users should see which other tasks and notes reference it — a backlinks panel.

Notes already have this via `NoteEntity.outgoing_links` (v6→v7 migration) and `getBacklinkNotes`. Tasks need the same infrastructure.

## Decision

### Storage: `outgoing_links` column on `tasks`

Tasks gain a new column mirroring the notes pattern:

```
tasks.outgoing_links TEXT NOT NULL DEFAULT '[]'
```

Stored as a hand-rolled JSON array of URL strings, e.g. `["task://abc","note://def"]`. This keeps the storage format identical to notes and avoids a separate join table.

**Why not a join table?** A `task_links` join table would require separate upsert/delete logic per link. The JSON column lets us use the same `saveOutgoingLinks()` pattern already proven on notes — one DB write per task update.

### Extraction: plain-text regex (not HTML)

Task descriptions are plain `String?`, not HTML. A separate `extractOutgoingLinks(text: String)` in `feature/tasks/data/TaskOutgoingLinks.kt` uses `Regex("""task://[a-zA-Z0-9_-]+|note://[a-zA-Z0-9_-]+""")` to find all URLs. This differs from `feature/notes/OutgoingLinksExtractor` which parses HTML from the rich-editor output.

### Write path: `TaskRepositoryImpl`

`saveOutgoingLinks()` is called from both `create()` and `update()` — every time the task is saved, outgoing links are recomputed from the current description and written to the `outgoing_links` column. This is the same pattern as `NotesRepository.setOutgoingLinks()`.

### Read path: `InternalLinkRepository`

Two new methods on `InternalLinkRepository`:
- `getBacklinkTasks(taskId)` — tasks linking to the given task
- `getNotesLinkingToTask(taskId)` — notes linking to the given task

Both are `suspend` functions returning `List<T>` directly (not `Flow`). They are called from `TaskDetailViewModel` when the observed task changes, stored in `MutableStateFlow` fields, and included in `TaskDetailUi`.

### Migration: v20 → v21

`Migration20To21 : AutoMigrationSpec` adds `outgoing_links TEXT NOT NULL DEFAULT '[]'` to `tasks`. Room generates the `ALTER TABLE tasks ADD COLUMN outgoing_links TEXT NOT NULL DEFAULT '[]'` automatically — no custom SQL needed.

## Consequences

- `TaskEntity` has new `outgoingLinks: String = "[]"` field with default
- `TaskDao.getBacklinkTasks` — LIKE query on the JSON column: `outgoing_links LIKE '%task://' || :taskId || '%'`
- `NoteDao.getNotesLinkingToTask` — same pattern for `task://` scheme in notes
- `FakeAppDatabase` fakes updated for both new DAO methods
- `TaskDetailDeps.linkRepo: InternalLinkRepository? = null` — nullable so existing tests pass without a fake link repo
- Backlink display: `LinkedBacklinksCard` composable rendered via `extraSections` in `TaskEditorContent` model-based overload

## Alternatives considered

**Join table `task_outgoing_links`**: Would require separate insert/delete vs. single JSON column write. Rejected for simplicity.

**Compute backlinks on read (no column)**: Scan all task descriptions on every read. Rejected — O(n) per request vs. O(1) with stored column.
