---
title: The undo puts back what was there
date: 2026-11-03
status: accepted
tags: [tasks, undo, tags, checklist, restore]
---

# The undo puts back what was there

## Context

Three undo paths were added to the task screen in #1828 — the undo of a removed tag
(#1528), of a deleted checklist item (#1529), and of a deleted subtask (#1531). The
review that followed found four ways in which they did not put back what was there.

- **A removed tag was written from a snapshot.** The removal wrote the tag set of the
  task the screen last saw (`taskFlow.value`), while the undo read the live set from
  the repository. A tag added to the task between those two writes was erased by the
  removal — the write said "the task has these tags" about a set that had already
  moved on.
- **The undo of a tag removal undid the user's own "do not propose this".** A tag the
  user took off is recorded as suppressed for the AI: the AI must not bring it back on
  its own. The undo was written as an ordinary user edit, and an ordinary user edit
  clears the suppression of the tag it adds — so putting the tag back let the AI
  propose the very tag the user had just removed.
- **A put-back checklist item was a new item.** A checklist item is deleted for real:
  the row leaves the table. The put-back wrote a row that was not there, and the row it
  wrote claimed to have been created just now and to have never been toggled. The
  content survived; the identity of the row did not.
- **`restore` restored anything it was given.** The query cleared `archived_at` on any
  row of the user — a task that was never deleted included — and the subtask undo did
  not ask whether the task it restored was a child of the parent whose screen offered
  the undo.

## Idea

An undo is not a new edit: it is the reverse of the edit that happened. So it reads the
current state, writes back what was there, and refuses what it cannot recognise.

## Decision

- **A write that follows a removal reads the current set from the repository.** The
  removal of a tag reads the tags the task carries now (`getTagIds`), and writes that
  set minus the tag. A tag that is not on the task any more is not removed again, and
  no undo is offered for it.
- **The undo of a tag removal is an actor of its own** — `TagEditActor.UserUndo`. The
  tag comes back, and the suppression stands: the AI stays quiet about the tag the
  user took off, because putting it back is the undo of that removal and not a new
  decision to have it.
- **A row that is put back carries its own row data.** `ChecklistItem` carries
  `createdAt` and `rowVersion`; the repository prefers the row that is there, then the
  values the item carries, then the defaults. An item that was read from the database
  and put back after a delete is the row the user had.
- **`restore` restores what is in the trash.** The query requires
  `archived_at IS NOT NULL`, as the tag restore has always required
  `deleted_at IS NOT NULL`; a task that was never deleted cannot be restored. The
  subtask undo asks one more question before the write: the task it puts back has to
  be a child of the parent that offers the undo.
- **The three tag intents have a type of their own.** `TaskTagIntent` is the marker
  they share, so the slot's `when` over them is exhaustive and has no branch that
  silently does nothing.

## Rationale

The first two defects are the same defect seen from two sides: the undo was written as
an ordinary edit, and an ordinary edit says what the user wants *now*. But the user
did not say anything now — the screen said, and the screen's own words were a
snapshot. Writing back the state that is there, and only that, is what makes the undo
the reverse of the edit rather than a second, unrelated edit.

The third defect is the price of the delete being real. A row that is gone cannot be
undeleted, only written again, and a row written again is a new row unless it is told
what it was. The two fields travel with the item for that reason and for no other:
they are the fields that make a row a continuation of the one before it.

The fourth defect is a boundary the undo crossed. A restore is a claim about one
specific row — the row the user deleted — and a query that restores every row of the
user it belongs to is a claim about all of them. The subtask undo is a claim about one
child of one parent, and the parent is the only thing that can tell whether the task
it was given is that child.

## Consequences

- The read and the write of a tag removal are still two steps: a tag added by another
  device in the window between them is lost. Closing that window needs a transaction
  over both steps, and the repository is where it would go.
- `ChecklistItem` carries two fields that are null for an item that was never read
  from the database — the previews and the editors that build an item do not know
  them, and they do not need to.
- `restore` of a task that is not in the trash now fails instead of succeeding
  quietly. Every caller that restores a task restores one it has just deleted, or one
  it has opened from the archive; the seed writer restores the trashed row it keeps
  for that purpose.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/slot/TaskEntitySlot.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/slot/TaskChildrenSlot.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tags/domain/model/TagEditActor.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/checklist/ChecklistIds.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/database/Daos.kt`
- `docs/decisions/2026-09-08-task-restore-undo.md`
- #1528, #1529, #1531, #1828
