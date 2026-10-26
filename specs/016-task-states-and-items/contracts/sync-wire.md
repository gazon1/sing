# Contract: The Task Document on the Wire

**Feature**: `016-task-states-and-items` · **Date**: 2026-10-26

The sync document for a task is the contract between this device and the Supabase
server. This feature adds one field to it.

---

## What changes

| | |
|---|---|
| **Field** | `workflowState` |
| **Type** | string, one of `"Open"`, `"NextUp"`, `"InProgress"` |
| **Written by** | the client |
| **Accepted by the server** | only once the server's `sync_field_allowlist` has a row for it — see *The server obligation* below |

Nothing else about the task document changes. `dependsOn` keeps carrying the blocking
projection; the relation kinds are unchanged.

---

## How the field list is derived

The client does **not** keep a hand-written list of wire fields. `SyncContract.WIRE_FIELDS`
is derived from each model's serialization descriptor, and `SERVER_FIELDS` is the wire
list minus the four server-managed names (`id`, `userId`, `serverVersion`, `hlc`).

So the moment `workflowState` is a field of the task model, it is on the wire. The
**server's** list is a different thing and is not derived — see below.

---

## The server obligation

The server decides what it accepts from a patch by looking the field up in its
`sync_field_allowlist` table. A field the client writes that the server has no row for is
**silently dropped** — the patch succeeds, and the field never arrives.

The source of truth for that table on the client side is
`config/docs/sync-field-allowlist.json`:

```json
"task": {
  "wire": [ …, "seriesId", "workflowState", "createdAt", … ],
  "serverWritable": [ …, "seriesId", "workflowState", "createdAt", "updatedAt" ]
}
```

From it:

```bash
# 1. regenerate the exported list from the Kotlin derivation
./gw :shared:exportSyncFieldAllowlist

# 2. regenerate the seed SQL from the exported list
python3 infra/gen_sync_allowlist_sql.py

# 3. the dated migration that actually inserts the row
#    supabase/migrations/<date>-sync-workflow-state.sql
```

**The dated migration is not optional.** The seed file is what a fresh database is built
from; the dated migration is what an **existing** database gets. Without it, an existing
deployment keeps the old table and drops the field.

The gate `sync allowlist seed matches contract` fails when the server names a field the
client does not write. It does **not** fail when a field the client writes is missing from
the server — so the omission is silent.

> **The debt to avoid.** `task.seriesId` and `project.closedAt` are exactly this debt
> today: open issue #1050, *"The server's field table is owed task.seriesId and
> project.closedAt"*. The gate is green, the field is on the wire, and the server drops
> it. This feature must not add a third.

---

## Backwards compatibility

| Situation | Behaviour |
|---|---|
| A new build reads a document from an old build | the field is absent → the task reads as `Open` |
| An old build reads a document from a new build | the old build's task model has no such field → it is ignored on read; if the old build writes the task back, the field is dropped from that write |
| A server without the allowlist row receives a patch | the field is dropped; everything else in the patch applies |
| A server with the allowlist row receives a patch | the field is stored |

The last row is why the dated migration is part of this feature rather than a follow-up:
without it, the feature works on one device and silently does not work across devices.

---

## What this contract does **not** cover

**The dependency relation kinds do not travel.** `task_dependencies` is a cross-ref that
does not implement `SyncableEntity`, and the task document carries only the blocking
projection. A `FOLLOWS_UP` edge therefore exists on the device where it was made and not
on another device.

This is recorded, not fixed, by `2026-10-01-typed-task-dependency-links.md:105,124`
("Deferred: add `user_id` to `task_dependencies` to enable compound FK and unblock
sync").

The consequence for this feature: **a follow-up marking is device-local.** The task
carries the marking on the device where the relation was made. The backup format is
different — see [backup-format.md](backup-format.md) — and the relation *does* survive a
backup.

---

## Files

| File | What it holds |
|---|---|
| `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncContract.kt` | the derived wire and server field lists — **unchanged**, the derivation picks the field up by itself |
| `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/model/Task.kt` | the model the derivation reads — **+ `workflowState`** |
| `config/docs/sync-field-allowlist.json` | the exported list — **+ `task.workflowState`** |
| `supabase/sync_field_allowlist_seed.sql` | generated from the exported list |
| `supabase/migrations/<date>-sync-workflow-state.sql` | **NEW** — the row for an existing database |
