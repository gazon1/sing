---
title: "NoteEntity dual task linkage — FK enforcement and wikilink removal"
date: 2026-10-02
tags: [tech-debt, database, room, migration]
status: accepted
---

# NoteEntity dual task linkage — FK enforcement and wikilink removal

## Context

`NoteEntity` stores task linkage in two parallel columns:

| Column | Type | Content |
|--------|------|---------|
| `outgoing_links` | `TEXT NOT NULL DEFAULT '[]'` | JSON array of `task://<id>` URI strings |
| `task_id` | `TEXT` (nullable) | Direct task ID, NULL when note is not attached |

`outgoing_links` was originally populated by `NotesRepositoryImpl.createForTask` writing `"task://$taskId"` into the array. The `task_id` column was added later as a proper foreign key, but the `outgoing_links` write was never removed.

**MR-0 (per-connection pragma)** enabled `PRAGMA foreign_keys = ON` on every connection. This makes the `FOREIGN KEY(task_id) REFERENCES tasks(id) ON DELETE SET NULL` constraint in `NoteEntity` actively enforced by SQLite.

## Decision

### MR-12a — Add FK constraint to `NoteEntity`

1. **Schema v26**: Add `FOREIGN KEY(task_id) REFERENCES tasks(id) ON DELETE SET NULL` to `NoteEntity`.
2. **Entity**: Add `foreignKeys = [...]` to the `@Entity` annotation on `NoteEntity`.
3. **Migration**: Manual `Migration25To26` (SQLite does not support `ALTER ADD CONSTRAINT` — table must be recreated).
4. **Backfill**: No data migration needed — `task_id` and `outgoing_links` are already consistent for all rows created by `createForTask`. Historical rows that link to a now-deleted task will violate the FK and cause the migration to fail; those rows should be treated as data bugs, not migration bugs.
5. **Index**: `index_notes_task_id` already exists from v23.

```kotlin
// Entities.kt
@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("task_id"), /* ... existing indices */],
)
data class NoteEntity(
    val taskId: String? = null,
    // ...
)
```

### MR-12b — Remove `task://` from `outgoing_links` writes

After MR-12a lands, `NotesRepositoryImpl.createForTask` no longer writes `"task://$taskId"` into `outgoing_links`. The `outgoing_links` column retains its role for `note://` links (from other notes) and remains as the general wikilink store.

```kotlin
// NotesRepositoryImpl.createForTask — before
outgoingLinks = JSONArray(listOf("task://$taskId")).toString(),

// After MR-12b
outgoingLinks = "[]",  // Only note:// links are written here by other operations
taskId = taskId.value, // FK column carries the structural reference
```

## Why not a combined MR?

MR-12a changes the database schema and requires a schema migration that can fail on bad data. MR-12b only changes application code. Splitting them means MR-12a can be tested and rolled back independently.

## Consequences

- **FK enforcement** means inserting a note with a non-existent `task_id` now throws `ForeignKeyConstraintException` instead of silently succeeding.
- **No FK index skip**: the existing `index_notes_task_id` means queries filtering by `task_id` remain efficient.
- **Ongoing wikilinks**: `outgoing_links` still contains `note://<id>` strings from note-to-note links. These are not enforced by the DB and remain an application-level concern.

## Implementation notes

- `Migration25To26` must recreate the `notes` table because SQLite `ALTER TABLE ADD CONSTRAINT` is not supported.
- The migration should be tested with `AppDatabaseFactoryJvmTest` pattern: create v25 DB, insert note with `task_id = NULL` and `outgoing_links = '["task://abc"]'`, run migration, verify `task_id = NULL` (FK was already NULL) or `task_id = 'abc'` (backfilled from `outgoing_links` if the FK constraint is relaxed during backfill).
- The `outgoing_links` → `task_id` backfill direction is **from** the wikilink array **to** the FK column, which is the inverse of MR-12b's removal direction.
