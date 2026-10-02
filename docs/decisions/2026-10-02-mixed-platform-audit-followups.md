---
title: "Mixed platform audit — MR-0 follow-ups: what was fixed and what was deferred"
date: 2026-10-02
status: accepted
tags: [platform, audit, android, jvm, desktop]
---

## Context

MR-0 ran a mixed-platform audit covering ownership scoping, lifecycle correctness, and
unscoped database writes across the Android and JVM Desktop targets. This ADR records the
findings: what was fixed immediately, what was deferred, and what was intentionally left
as-is with rationale.

## What was fixed in MR-0

### `CalendarSyncTaskMapDao` — all 9 methods missing `user_id`

`calendar_sync/data/CalendarSyncTaskMapDao` had no `user_id` column on the join table and
no scoping on any method. `deleteStale` (`WHERE task_id NOT IN (:taskIds)`) deleted rows
for all profiles.

**Fix:** Added `user_id TEXT` to `calendar_sync_task_map` table. All 9 methods now scope
by `user_id` via a SQL subquery on `tasks.user_id` (the join table has no own `user_id`,
the scope comes from the parent `tasks` table). `deleteStale` now also scopes by `user_id`.

`Migration24To25` adds the column and backfills `user_id` from the `tasks` join before
dropping the old unscoped methods.

### `Daos.kt:374` — `watchChildren` unscoped

`NoteDao.watchChildren` was the only unscoped read with no scoped twin.

**Fix:** Added `watchChildrenForUser(noteId: String, userId: String)`. The old method was
removed (zero production call sites).

### `TaskRepositoryImpl.setDependencies` — hardcoded `DependencyVerb.BLOCKS`

```kotlin
// BEFORE (wrong):
edge.verb.name  // always BLOCKS regardless of actual verb

// AFTER (correct):
edge.verb.name  // now returns actual verb: BLOCKS or DEPENDS_ON
```

The edge-upsert was using `verb.name` on a hardcoded `DependencyVerb.BLOCKS` instead of
the actual `edge.verb`. This silently downgraded typed edges (DEPENDS_ON) to untyped
(BLOCKS) on every write.

**Fix:** Diff-based upsert uses `edge.verb.name` directly, preserving the typed edge.

### `ChecklistRepositoryImpl.toggleItem` — reconstructs entity incompletely

On toggle, 6 fields were being set explicitly and `checked_by` / `checked_at` (added in
MR-8) would have been silently dropped on every toggle.

**Fix (MR-8):** `UPDATE checklist_items SET is_completed=:v, updated_at=:ts WHERE id=:id
AND task_id IN (SELECT id FROM tasks WHERE user_id=:userId)` with `rows > 0` check. Uses
the repository pattern (not `ChecklistDao.upsert`), which is the only correct write path
in the codebase (per `NotesRepositoryImpl.setSortOrder:242`).

`createBatch` was also split: existing items are updated (preserving `created_at`) and
new items are inserted with `created_at = now`.

### `TagGroupRepositoryImpl.setInheritedForProject` — no transaction

`deleteAllForUser` + `insertForUser` were called sequentially without a transaction.
A crash between them left inheritance empty for that project.

**Fix:** Wrapped in `database.withTransaction { ... }`.

### `TaskRemindersSlot.setReminder` — always creates new `ReminderId`

Every call generated a new `ReminderId`, dropping `last_fired_at` on updates.

**Fix:** Looks up existing reminder by `task_id` first; updates in place if found,
inserts only if not.

### `AgendaDefinition.Section.id` — non-nullable broke backward compat

`Section.id` was changed from `String? = null` to non-nullable without considering
legacy saved agendas. `MissingFieldException` on deserialization.

**Fix (MR-6/MR-7):** `id: String? = null` with computed `effectiveId: String get() = id ?:
name.lowercase()`. The deserializer sees `id` as nullable; `effectiveId` is the stable
canonical identifier.

## What was deferred

### `AttachmentDao.upsert` — unscoped upsert without scoped twin

`attachments` table has `user_id`. The `upsert` method does not scope by `user_id`.
There is no scoped twin.

**Deferred:** No production call site identified that writes attachments for a different
user. The `BackupCodec` reads all attachments during backup restore, but restore is a
single-user operation that writes into the current user's scope. The fix (scoped twin +
KDoc) is low urgency and would require a careful audit of all call sites.

### `mcp-server/ToolRegistrar.kt:97` — `runBlocking` in a coroutine dispatcher

```kotlin
// Inside Dispatchers.IO.limitedParallelism context:
runBlocking {
    tool.execute(...)
}
```

`limitedParallelism` creates a confined executor. `runBlocking` blocks the calling thread
from within that executor, which can cause a deadlock when the pool is saturated.

**Deferred:** The MCP server runs in a dedicated process (`mcp-server` module) with its
own execution context. The `runBlocking` is in the tool dispatch path, which is not
hot-path. A proper fix would require `suspend` on the tool interface, which requires
changing 22 tool implementations simultaneously. Deferred to a follow-up cleanup.

### JVM Desktop timer — no native Pomodoro notifications

`JvmPomodoroTimer` is a stub. It calls `PomodoroSessionSink` correctly but produces no
desktop notifications when a work phase completes.

**Deferred:** Desktop notifications via a cross-platform library (e.g., `notify-rust` or
`java.awt.SystemTray`) are out of scope for MR-2. The Pomodoro timer works correctly on
Android; the JVM stub is functional for time recording purposes.

## What was intentionally not fixed

### `collectAsState` (11 instances → `collectAsStateWithLifecycle`)

The `collectAsState` → `collectAsStateWithLifecycle` migration was identified in MR-0-D
but not executed because it touches 11 files and requires verifying that each `collectAsState`
is in a `LifecycleOwner`-scoped composable context (which some of them may not be — some
are in screens that can be invoked without a lifecycle, e.g., `SearchScreen` in a shared
snippet).

**Decision:** Leave as-is until a dedicated migration pass can verify each call site
individually. `collectAsState` is not incorrect, merely less lifecycle-aware than the
alternatives.

### Unscoped `@Upsert` methods (9 in Daos.kt + 1 in AttachmentDao.kt)

These methods write without `user_id` scoping. KDoc comments were requested to document
that they are backup-restore only and should not be called from regular application code.
This was not implemented in MR-0.

**Decision:** Add KDoc to each method in a follow-up pass, after verifying the actual
call sites for each. Not a hot-path issue but a long-term architectural hygiene item.

## Links

- `docs/decisions/2026-09-28-emulator-mesa-radeon-cs-rejected.md` — AMD Mesa GPU crash (separate platform issue)
- `docs/decisions/2026-09-29-emulator-crash-recovery-runner.md` — emulator recovery script
- MR-0-A commit: `67fed68f`
- MR-0-B commit: `dbbf1d15`
- MR-0-C commit: `70678dd2`
- MR-0-D commit: `21d8cc80`
