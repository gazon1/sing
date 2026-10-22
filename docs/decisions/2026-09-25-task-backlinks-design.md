---
title: Task Backlinks — Design
date: 2026-09-25
status: accepted
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

## Addendum — the match is exact, and the URL comes from the scheme table (2026-10-22)

The design above made the read path a `LIKE` over the JSON column:

```sql
outgoing_links LIKE '%task://' || :taskId || '%'
```

Three things were wrong with it, and each of them was a defect rather than a shortcut.

1. **The pattern matched more than the link.** `LIKE '%task://abc%'` also matches
   `task://abcd`, and it matches a link that merely *contains* the id — a text like
   `see task://abc in the log`. A backlink list that shows a link which is not a link
   to that task is worse than no backlink list.
2. **The id was interpolated into a pattern without escaping.** `%` and `_` are
   wildcards in `LIKE`, so an id holding one of them matched something else.
3. **The scheme was written as a literal in the query.** `task://` appeared in SQL, in
   the Kotlin that built the pattern, and in `core/links` — three places to keep in
   step, and the SQL copy was the one nothing tested.

The column keeps carrying the URL — the format a person sees and the format the
extractor writes is not changing. What changed is how the column is *read*:

```sql
EXISTS (SELECT 1 FROM json_each(outgoing_links) WHERE value = :url)
```

`json_each` turns the JSON array into rows of whole values, and the comparison is `=`
against the whole URL. The URL itself is built by `InternalLinks.urlFor` from
`InternalLinkSchemes`, so the query no longer knows what a scheme looks like.

The cost is that the comparison cannot use an index on the column. For a column of
a person's own links — a handful of entries on one row — that is the right trade: an
exact answer over a cheap scan beats a fast answer over a wrong one.

The same change reached the three queries that had the pattern, and the prefix the
migration needed: the migration that backfills `notes.task_id` derives its prefix and
its length from `InternalLinkSchemes` instead of writing `9` and `'task://'` as
numbers and literals.

