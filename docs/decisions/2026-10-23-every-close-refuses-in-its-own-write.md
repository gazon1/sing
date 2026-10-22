---
title: Every close refuses in its own write
date: 2026-10-23
status: accepted
tags: [tasks, dependencies, sync]
---

# Every close refuses in its own write

## Context

`2026-10-22-the-blocking-rule-has-one-owner` moved the close rule into the
statement: `TaskRepository.complete` completes a task only while no blocking
prerequisite is open, and the surfaces ask the user when the write refuses.

Two paths still closed tasks without going through that statement:

- **the recurring close.** Completing a recurring task rolls the recurrence
  forward, which is a different write — `CompleteRecurringTaskUseCase` wrote
  the whole row with `repo.update(task.copy(completedAt = …))`. A full-row
  update writes `completed_at` whether or not anything is in the way, so the
  guard could not be part of it. The slot worked around this with a check
  *before* the write: `Blocking.activeBlockers(task, allTasks.value)`, then
  ask, then write. That is the check-then-write the whole consolidation was
  about — the window between the two was where another device reopened the
  prerequisite.
- **the bulk close.** `TaskMutationsUseCase.bulkComplete` checked the
  selection before writing (that part was right) but the refusal reached the
  user as the raw message of an exception in a snackbar: "These tasks have
  unfinished prerequisites: …". A single close asked the user a question with
  a dialog; a bulk close reported a fact.

So the rule was the rule in one place and a convention in the other two, and
the two places where it was a convention were exactly the two a user would
notice: a recurring task, and a selection.

## Idea

Make every close go through a statement that can refuse, and make every
refusal reach the user as the same question.

## Decision

1. **The recurring close is two narrow writes, and the completion is the
   guarded one.**
   - `TaskRepository.completeOccurrence(id, completedAt, updatedAt, force)` —
     the same guarded statement the ordinary close uses.
   - `TaskRepository.moveDueDate(id, nextDue, updatedAt)` — the roll-forward,
     narrow on purpose: a full-row update would carry the completion back over
     the guard that has just refused it.
   - The use case owns the transaction (`UnitOfWork`), so the completion, the
     copies of the missed occurrences and the move to the next date are one
     write. A refusal rolls the whole thing back.
2. **The check before the recurring write is gone.** The slot asks when the
   write refuses, exactly as it does for an ordinary task. `force` is the
   confirmed path, and it is the same `force` the ordinary close takes.
3. **A bulk close asks the same question.** `TaskMutationsUseCase.bulkComplete`
   takes a `force`, and the agenda publishes a `BlockedBulkCloseRequest` when
   the write refuses: the tasks in the way are named, the selection is held,
   and the answer decides. The selection survives a refusal, because nothing
   was written and the user may want to pick another action.
4. **The refusal of a recurring close is a `BlockedTasksException`** — the same
   exception the bulk path raises. One exception for one meaning: "this close
   was refused, and these tasks are in the way".

## Rationale

- **A check that runs before the write has a window, and the window is the
  bug.** The recurring path was the last place where the rule was a
  convention. The whole point of the guarded statement is that the answer and
  the write are the same fact; a path that asks first cannot have that.
- **The copies must not be written before the guard.** A catch-up series writes
  a copy of every missed occurrence. If the guard ran after those writes, a
  refusal would leave the copies behind and the retry would write them twice.
  The guard is first, and the whole roll-forward is one transaction, so a
  refusal leaves nothing behind.
- **A question is not a message.** "These tasks have unfinished prerequisites:
  t1, t2" names the ids and offers nothing. The single close already had the
  right shape — a dialog that names what is in the way and offers "Close
  anyway" — and the bulk close is the same question about a selection.
- **One exception for one meaning.** `BlockedTasksException` now means "a close
  was refused" everywhere it appears, and the surfaces can handle it without
  knowing which path raised it.

## Consequences

- The recurring path no longer has a window. It is the last close in the app
  that goes through the guarded statement.
- `CompleteRecurringTaskUseCase` takes a `UnitOfWork` and writes through two
  narrow statements instead of one wide one. A concurrent edit to another field
  of the task is no longer overwritten by a roll-forward.
- The agenda has a second confirmation state (`pendingBlockedBulkClose`) and a
  second dialog. The wording is a pure function
  (`blockedBulkCompletionText`), so it can be asserted without a Compose
  runtime.
- The recurring close is now transactional: a failure in the middle of a
  catch-up roll-forward leaves the series as it was, instead of half-rolled.

## Links

- `docs/decisions/2026-10-22-the-blocking-rule-has-one-owner.md`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/usecase/CompleteRecurringTaskUseCase.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/usecase/TaskMutations.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/slot/TaskCompletionSlot.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/viewmodel/AgendaViewModel.kt`
