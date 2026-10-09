---
title: "Undoable delete: the write comes first, the offer comes second"
date: 2026-10-09
tags: [agenda, notes, undo, ux, concurrency]
status: accepted
---

## Context

`AgendaViewModel.handleTaskDelete` was the entire delete path for the agenda. It resolved
a title, cancelled the previous window, wrote a pending marker, emitted an event and
armed a five-second timer. It never called `taskRepo.softDelete`. The user saw
`"Buy milk" deleted` and a working undo affordance; the task stayed in the database and
kept firing its reminder.

Two corroborating facts that the delete was never written rather than broken: the
dependencies it would have needed — `AgendaDeps.reminderScheduler` and
`AgendaDeps.currentUser`, both documented *"For cancelling reminders when a task is
deleted"* — were referenced nowhere in the file, and `cancelByTask` appeared zero times.

`NotesListViewModel` had the mirror defect: it deferred `repo.delete` until the window
expired, so the note sat on screen for five seconds and then vanished by itself, and its
Undo issued a `restore` against a live row. Its KDoc claimed to match `AgendaViewModel`,
which is how the broken behaviour became the stated reference implementation for a second
feature.

ADR `2026-09-08-task-restore-undo` already specified the contract — the delete has
happened, `restore` reverses it, the pending slot holds one item — and both features
satisfied its postconditions while violating its precondition. That is why this is worth
a decision record: the contract was written down, and following the document was
insufficient, because nothing forced the write to exist.

## Decision

1. **The delete is committed before the offer is presented.** Not in the same statement,
   not optimistically, not deferred to a timeout. Undo is offered only after the write
   returns success.

2. **The pending marker clears on a successful reversal only.** A failed reversal leaves
   the offer standing and reports the failure. This is `delete-safety-feedback` Phase 1
   and #78, and it is deliberately asymmetric with the happy path: on success there is
   nothing left to offer, on failure there is.

3. **The ViewModel's undo window is the only clock.** The snackbar is presented
   `Indefinite` and dismissed when the marker clears. A second duration on the UI side is
   a second lifetime that can drift from the one that decides whether `restore` is still
   valid.

4. **Reminders are cancelled before the delete, and a failed cancellation aborts it.** A
   scheduled reminder for a task the user no longer has is the failure this work exists
   to remove.

5. **A failed cancellation is not compensated.** The reminder stays cancelled. The
   cancellation API returns nothing that would let it be rescheduled, so restoring it
   would require a repository change this work does not justify.

6. **Two counters, not one, and not a `Mutex`.** `deleteSequence` is claimed at dispatch
   time and decides which delete owns the single affordance; `undoSlot` is advanced only
   on success and scopes the timer. `BackgroundScope` runs on `Dispatchers.Default`,
   which is genuinely multi-threaded, so two deletes in quick succession really do
   interleave and completion order is not request order.

## Rationale

An affordance offering recovery from an action that did not happen is worse than no
affordance: it tells the user their data is in a state it is not in, and it offers a
path that cannot work. Ordering the write first makes the two claims — "it was deleted"
and "you can undo it" — both checkable against the repository.

Clear-on-success follows from the same principle applied to the failure side. Clearing
the marker before knowing the reversal worked removes the only recovery path at the
moment the user needs it, and dismissing the snackbar with it makes the failure
invisible. The cost is that the affordance can outlive its usefulness, which
`REQ-UD-010` bounds rather than pretends to solve.

Two counters rather than one because a single counter cannot express the two distinct
questions. "Which delete owns the offer?" is a request-order question and must be
answered at dispatch. "Has the offer been consumed?" is a state question and may only be
advanced by the one operation that consumes it. A `Mutex` would serialise the writes
without answering either — it prevents interleaving rather than resolving it, and the
race it would guard against is a lost update, not a corruption.

## Consequences

- Agenda and notes now commit at tap. Anything downstream that assumed notes lingered
  during the window is wrong.
- `AgendaUiEvent.UndoDelete` was removed: the pending marker carried the same id and
  title, and the screen collected the event into an empty branch. `NotesUiEvent.UndoDelete`
  and its empty screen arm were **kept** — an existing test asserts it, and removing it is
  a separate change. The asymmetry is deliberate and temporary.
- A lost reminder on a failed delete is accepted, and documented as a one-directional
  invariant rather than an oversight.
- The pending slot holds at most one delete; a newer delete supersedes an open window and
  the superseded timer is inert.
- This departs from ADR `2026-09-08` §3 for the agenda path. That ADR has the delete
  already performed and is correct; agenda simply did not implement it. The departure is
  that the marker is now consumed by success alone rather than by "an undo was requested",
  because request-and-fail was silently lossy.

## Links

- Tracker: #253 (the defect and its blast radius), #78 (clear-on-success), #80
  (countdown), #104 (delete surfaces policy)
- OpenSpec change: `openspec/changes/a-delete-that-was-never-issued-was-reported-as-deleted`
  — `undoable-delete` `REQ-UD-001`…`011`, `intent-routing` `REQ-IR-001`…`002`
- Prior decision: `docs/decisions/2026-09-08-task-restore-undo.md`
- Policy for the remaining work: `openspec/changes/delete-safety-feedback`
- Skill: `singularity-todo-ui-event-vs-state`, `singularity-todo-vm-intent-pattern`