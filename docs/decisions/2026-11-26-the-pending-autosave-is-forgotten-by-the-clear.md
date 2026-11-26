---
title: "The pending autosave is forgotten by the clear that settles the draft"
date: 2026-11-26
status: accepted
tags: ["ui", "draft", "mvi", "tasks"]
issue: "#3256"
epic: "#3225"
---

# The pending autosave is forgotten by the clear that settles the draft

Settles #3256: a debounced autosave that is already scheduled must not write a
draft back after a save or a discard has cleared the store.

## Context

`DraftMviViewModel` runs one debounced autosave. #3255 serialised it against the
explicit save with `writeLock`, so the two writers can no longer overwrite each
other out of order. Ordering is not cancellation: a write that is *already
scheduled* still lands, on whichever side of the clear the lock puts it.

The sequence, from the create editor:

1. the person types, and the 500 ms debounce starts;
2. they press Save before it fires;
3. `persist()` creates the task, and the create editor's `onSaved` clears the
   draft store — the store is empty;
4. the pending write fires and puts the draft back;
5. opening the create editor again restores that draft, and submitting it
   creates **a second task** — `TaskDraft` carries no task id, so
   `CreateTaskFromDraft` creates on every save.

The same window exists on the create editor's Discard, where the thrown-away
text comes back.

`NoteEditor` is not affected: it passes `restore = { null }`, so a resurrected
entry is never read back — its loss is a stale row, not a duplicate.

## Idea

Three ways out, and the issue names them:

- **(a) cancel the pending write** — the save/discard path invalidates the
  debounce so nothing lands after the clear. Smallest change; the base class
  had no handle on the collector's pending emission.
- **(b) let it land** and make `restore` ignore a draft older than the entity's
  creation — needs a timestamp or an "already submitted" marker in the store.
- **(c) leave the ordering as pinned** and accept the duplicate, documenting it
  as intended. Cheapest, and wrong: a duplicate task is not recoverable by any
  edit, and the person is never told it happened.

## Decision

**(a), and narrowly: the clear forgets one value.**

`DraftMviViewModel` holds `forgottenDraft: D?` — the draft whose stored copy
was just cleared. The autosave collector compares each debounced value against
it, under `writeLock`, and skips the write when they are equal.
`forgetPendingAutosave(draft) { clear }` marks the value and runs the clear
inside one locked section, so a write either lands *before* the clear (which
removes it) or sees the marker and does not run at all.

Two callers, both of which really clear a store:

- `save()`, with the draft it persisted, next to `clearAutosave()`;
- `TaskCreateViewModel`'s Discard intent, with the draft it captured before
  launching, around `clearDrafts()`.

The base `discard()` deliberately does **not** call it: it keeps its autosave
entry as a standing safety net, and dropping the pending write there would turn
a resurrection into a dropped write — #2724, a different defect.

## Rationale

**A value, not a flag.** The stale write is the one carrying the draft that was
cleared. A boolean ("suppress the next autosave") would also drop text typed
*while the save suspends* — text no store holds, which the checkpoint contract
in `2026-11-26-the-draft-keeps-one-value-and-one-snapshot.md` says must stay
dirty and be stored. The comparison is exact, so the keystroke survives.

**Under the lock, not beside it.** Marking outside `writeLock` leaves the window
this change exists to close: a write reads "not forgotten", suspends on the
store, the clear runs, and the write lands on top of it. Taking the lock
serialises the decision with the write it filters.

**No timestamp, and nothing new in the store.** Option (b) would put
editor-session state into the draft store and make `restore` reason about it;
the editor already knows which value it settled, so nothing has to be persisted
to remember it.

**The marker stays set on the saved value.** A person who retypes exactly what
was just saved must not recreate the draft row of an entity that already
exists — dropping that write is the point, not a side effect.

## Consequences

- `forgetPendingAutosave` is a seventh subclass seam, and one production
  consumer calls it (`TaskCreateViewModel`'s Discard). `save()` calls it itself,
  so an editor gets the save half without writing anything.
- `TaskCreateDraftBehaviorTest` states one contract instead of two contradicting
  ones: `a pending autosave cannot resurrect the draft the save cleared`, the
  same after the discard, and `a reopened editor starts blank after a save
  inside the debounce` — the sequence from the issue end to end.
- `NoteEditor`'s close path is unchanged and still loses edits made inside the
  debounce (its `discard()` keeps the safety-net entry). That is #2724, and this
  change does not close it.
- A write that is already *running* when a save clears the store is still
  ordered, not cancelled: it lands, and then the clear removes it. That is #3258
  — the same question `open()` asks of the entity switch (#3229), and it costs
  a wait plus a `SaveFailed` the user sees for a draft already settled.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/DraftMviViewModel.kt` — `forgottenDraft`, `forgetPendingAutosave`, the collector, `save()`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/TaskCreateViewModel.kt` — the Discard intent
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/tasks/TaskCreateDraftBehaviorTest.kt` — the three rows
- `docs/decisions/2026-11-26-the-draft-keeps-one-value-and-one-snapshot.md` — the checkpoint and `writeLock` this builds on
- `docs/decisions/2026-11-26-what-a-draft-subclass-may-override.md` — the seam list this extends
