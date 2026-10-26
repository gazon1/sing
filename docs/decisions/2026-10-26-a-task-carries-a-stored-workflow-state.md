---
title: "A task carries a stored workflow state"
date: 2026-10-26
status: accepted
tags: [tasks, domain, database, backup, sync]
---

# A task carries a stored workflow state

## Context

A task has work to do, but the app does not record what the user has claimed about
that work. "Blocked" and "overdue" and "in progress" are all inferred at read time,
which means there is no stored fact for "the user said this is the one they are
working on right now" and no stored fact for "the user said this is the one they
will do next". The result is that the task list can show a task as overdue or pinned
or recurring, but it cannot show the user's own claim about what the task is to the
user's work.

The decision to make this a stored, user-set state was settled with the user (see
the spec's *Clarifications* section): a stored workflow state plus derived markings
that can appear together.

## Idea

A column on the `tasks` table, three values, defaulting to `Open`. The state is
independent of completion: a completed task keeps its state, and reopening restores
the state it held.

## Decision

`tasks.workflow_state TEXT NOT NULL DEFAULT 'Open'` — a column on the same table as
`priority`, `kind`, `someday` and `is_pinned`, holding the enum entry name
(`"Open"`, `"NextUp"`, `"InProgress"`). The default is `Open` for pre-existing rows,
for new rows, and for rows written by a build that does not know the column. The
state is independent of the completion flag: completing a task does not change the
state, and reopening it restores the state it held. A completed task's state is
displayed but read-only.

## Rationale

- **One-to-one means a column.** A task has exactly one workflow state, so a separate
  table would be a one-to-one relation by construction. Every task list, every agenda
  evaluation and every sync document already selects from `tasks`; a join would land
  on the hot path of all of them for a value that belongs to the row. The alternatives
  table were rejected on those grounds.
- **Stored by name, not ordinal.** This is what `priority` and `kind` already do.
  The value is readable in the exported schema, in the sync allowlist and in a
  support request; and adding a fourth value later cannot renumber the rows already
  stored. An integer ordinal turns "adding a state" into a data migration and makes
  the schema export opaque.
- **Three values, not four.** `Open` (nothing claimed, the default), `NextUp` (the
  one the user will do next), `InProgress` (the one the user is doing now). A fourth
  stored value, `Waiting`, was considered and rejected: waiting on someone else is
  already derived from the task's real prerequisites, and a stored flag for it would
  give one fact two sources of truth that could disagree.
- **Independent of completion.** The user chose "only the subtask and checklist rows
  get checkboxes" for the create/detail question, which keeps the completion flag
  exactly as it is today. Separately, a task that was "in progress" when it was
  finished is a fact about how the work went, and clearing it on completion loses
  that. The state survives; it simply stops being actionable.
- **Hand-written migration, not auto.** The derived auto-migration for a changed
  `tasks` table rewrites the whole table; dropping it fires the foreign key that
  points `notes.task_id` at a task, and the notes lose their task.
  `Migration45To46` exists solely to add `tasks.series_id` without that rewrite, and
  this change is the same shape. A one-line `ALTER TABLE … ADD COLUMN` with a
  constant default is both simpler and safer than the auto-migration, and is legal
  in SQLite without a table rewrite.

## Consequences

- The database moves to schema version 47, with a hand-written `Migration46To47`
  registered in `AppDatabaseFactory.addMigrations` beside `Migration45To46`.
- The backup format moves to schema version 6: `TaskDto` gains a `workflowState`
  field, because a task record is a per-field DTO and a field added to the record
  has to be added to the DTO deliberately.
- The sync wire gains `workflowState` automatically: `SyncContract.WIRE_FIELDS` is
  derived from the model's serialization descriptor, so the field is on the wire
  the moment it is on `Task`. The **server** accepting it requires a dated migration
  in `supabase/migrations/` plus a row in `sync_field_allowlist` — without it the
  server silently drops the field on every patch (open issue #1050 is exactly this
  debt for `task.seriesId`).
- A `SetWorkflowState` intent is added to `TaskDetailIntent`; routing it and
  handling it in `TaskEntitySlot` gives the task screen the only write path for the
  state.
- A completed task's state control is frozen on the detail screen — the state is
  shown but cannot be changed, and the create screen never offered one because there
  is no task to state yet.

## Links

- `docs/decisions/2026-10-23-the-dependency-graph-has-two-projections.md`
- `docs/decisions/2026-10-01-typed-task-dependency-links.md`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabaseFactory.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncContract.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupFormat.kt`
