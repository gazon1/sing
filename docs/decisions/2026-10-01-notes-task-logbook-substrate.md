---
title: ADR: Notes ↔ Tasks Logbook Substrate
date: 2026-10-01
status: accepted
---
# ADR: Notes ↔ Tasks Logbook Substrate

- Status: Accepted
- Date: 2026-10-01

## Context

The task detail screen needs to show a chronological log of notes attached to a task ("logbook") and allow adding new notes directly from the task screen.

The existing note-to-task linkage is stored as a `task://<id>` token inside the `outgoing_links` JSON column on `NoteEntity`:

```sql
WHERE outgoing_links LIKE '%task://' || :taskId || '%'
```

This has three problems:

1. **Performance** — leading wildcard `LIKE '%...'` cannot use a B-tree index; every query does a full table scan.
2. **Referential integrity** — the JSON blob has no foreign-key constraint; a deleted task does not cascade-delete the relation.
3. **Reactive queries** — the DAO method is a one-shot `suspend` query; there is no reactive `Flow`-based watch that invalidates when `outgoing_links` changes.

The alternative is a real `task_id` column on `notes`.

## Decision

Add a nullable `task_id` column to `notes` with an index.

```kotlin
@Entity(
    tableName = "notes",
    indices = [
        // ...existing indices...
        Index("task_id"),   // new — enables O(log n) lookups instead of O(n) scan
    ],
)
data class NoteEntity(
    // ...existing fields...
    @ColumnInfo("task_id") val taskId: String? = null,   // new
)
```

Room migration `23 → 24` is a pure additive schema change (nullable column with no backfill). The existing `outgoing_links` JSON is **not removed** — it still carries note-to-note `note://` links and serves backward compatibility for any notes created on devices that have not yet migrated.

### DAO layer

`NoteDao.getNotesLinkingToTask` is updated to use the new column:

```kotlin
@Query("""
    SELECT * FROM notes
    WHERE task_id = :taskId
      AND user_id = :userId
      AND deleted_at IS NULL
    ORDER BY created_at DESC
    LIMIT 20
""")
fun watchByTaskForUser(taskId: String, userId: String): Flow<List<NoteEntity>>
```

The old `LIKE`-based method is removed (or kept only if an explicit consumer needs it — none identified in preflight).

### Repository layer

```kotlin
fun watchForTask(taskId: NoteId, userId: UserId): Flow<List<Note>>

suspend fun createForTask(
    taskId: NoteId,
    userId: UserId,
    title: String,
    bodyMarkdown: String? = null,
    bodyHtml: String? = null,
): Result<NoteId>
```

`createForTask` writes both `task_id = taskId.value` AND `outgoing_links` containing `task://<taskId.value>` for the transition period. When all devices have migrated, the `outgoing_links` write can be removed.

### TaskDetail integration

`TaskLogbookCollector` (plain collector, not a `FeatureSlot`) observes `watchForTask` and exposes `StateFlow<TaskLogbookState>`. The coordinator merges it with the existing six slots via `combineStates`. `TaskDetailDeps` takes `notesRepo: NotesRepository` as a **mandatory** (non-nullable) dependency — logbook is part of the task detail screen, not an optional feature.

### Navigation

`NotesStartRoute.EditorForTask(val taskId: String)` is a new sealed entry in `NotesStartRoute`. `TasksNavigator.openCreateNote(taskId)` navigates to `NotesGraph(EditorForTask(taskId.value))`. `NoteEditor` takes an optional `taskId: NoteId?` parameter; when present the editor pre-fills the task link and uses `createForTask` on save.

## Rationale

- **Indexable** — `task_id` with an index gives O(log n) lookups vs O(n) table scan.
- **Reactive** — `Flow`-based watch updates automatically when any note's `task_id` changes.
- **Minimal schema** — nullable column, no backfill, no migration of existing `outgoing_links` data required at this stage.
- **Backward compatible** — `outgoing_links` still written, existing queries continue to work.
- **Mandatory dep** — avoids the "declared-but-inert" defect class where an optional dependency silently provides null.

## Consequences

- Existing `LIKE`-based queries become dead code and should be removed in a follow-up cleanup.
- `outgoing_links` still carries `note://` tokens for note-to-note backlinks — those are a separate migration (out of scope for this epic).
- User-isolation tests must verify that `watchForTask` is scoped to the current user.
- Sync/outbox tests must verify that `createForTask` enqueues a sync event with the correct `task_id`.

## Related

- [ADR 0042: Typed task relationship links](./0042-typed-task-relationship-links.md) — the typed-link model that extends `task_dependencies`; this ADR covers note→task only.
- `docs/decisions/2026-10-01-notes-task-logbook-substrate.md` — this document
- Preflight audit: `NoteEntity`, `NoteDao`, `NotesRepository`, `TaskDetailCoordinator`, `TaskLogbookCollector`
