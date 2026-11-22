---
title: "Subtask order lives on Task.sortOrder and syncs on the wire"
date: 2026-11-22
status: accepted
tags: [tasks, subtasks, sync, backup, room]
---

## Context

Issue #2924 asks for reordering direct subtasks in the task detail Subtasks
section. Checklist rows already persist order in `checklist_items.sort_order`
and reorder the whole list in one Room transaction (`ChecklistDao.reorderForUser`).
Subtasks had no persisted order: `TaskRepository.observeSubtasks` filtered active
tasks by `parentTaskId` with no stable persisted ordering, so the UI could not
restore a user-chosen order after close/reopen or relaunch.

The issue required a decision before the schema change: put order on `tasks`
(joining the sync/backup wire contract) or in a local-only child-order table
(keeping wire untouched, but not surviving backup/restore on another device).
The acceptance criteria require order to survive close/reopen, app relaunch, and
be one write with failure leaving previous order intact.

## Idea

1. **`Task.sortOrder` column on `tasks` (chosen).** Simple, follows existing
   checklist/project `sort_order` patterns, and travels with the task row through
   Room, backup/restore, and sync. Requires schema migration, schema export,
   sync field allowlist, and server allowlist migration.
2. **Local-only child-order table.** Keeps wire untouched, but order would not
   survive backup/restore or another device. Conflicts with the issue's
   persistence expectation and would create a second source of truth for task
   children.

## Decision

Add `sortOrder: Int = 0` (`sort_order` in Room, `sort_order` on the wire) to
`Task`/`TaskEntity`. Subtask order is the persisted position among direct
children with the same `parentTaskId`, ordered by `sort_order ASC, created_at ASC`.
The field is on the wire and in `sync_field_allowlist` (`wire` and
`serverWritable`) so it survives backup/restore and syncs across devices.

Room migration `54->55` is manual: `ALTER TABLE tasks ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0`.
It is written by hand to avoid the derived auto-migration rewriting the whole
`tasks` table, which would fire `notes.task_id ON DELETE SET NULL`.

Reorder writes the full visible subtask id list in one Room `@Transaction`,
renumbering `0..n-1`, the same shape as checklist reorder. `TaskRepository.reorderSubtasks`
enqueues fresh sync for reordered rows. New subtasks append after the current
maximum child sort order.

## Rationale

Subtasks are tasks. Putting order on the task row keeps the model uniform and
lets existing task sync/backup paths carry the field without a parallel storage
system. The wire cost is one integer field on task documents, which is small and
consistent with other ordered entities.

A local-only table would solve only the local UI and would violate the issue's
persistence/backup expectations. It would also require reconciliation when tasks
are restored or synced.

## Consequences

- **Always** read direct subtask order through `TaskDao.watchSubtasksForUser` /
  `TaskRepository.observeSubtasks`, ordered by `sort_order ASC, created_at ASC`,
  not by filtering unsorted active tasks.
- **Always** reorder subtasks as one full-list transaction; do not swap two rows.
- **Always** keep subtask reorder scoped to direct children and the current user.
- **When adding subtask creation paths**, assign append sort order after the
  parent's current max child sort order unless an explicit sort order is supplied.
- **Sync/backup:** `task.sortOrder` is wire/server-writable and included in task
  snapshots/backups through the normal Task model. Server `sync_field_allowlist`
  must include `('task','sortOrder',true)`.

## Links

- Issue: https://github.com/gazon1/sing/issues/2924
- ADR: `docs/decisions/2026-09-08-task-1-level-subtasks.md`
- Checklist reorder reference: `core-database/src/commonMain/kotlin/com/singularity/todo/core/database/Daos.kt` (`ChecklistDao.reorderForUser`)
- Migration: `core-database/src/commonMain/kotlin/com/singularity/todo/core/database/Migration54To55.kt`
