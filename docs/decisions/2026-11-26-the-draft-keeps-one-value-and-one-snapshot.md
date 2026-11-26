---
title: "The draft keeps one value and one snapshot"
date: 2026-11-26
status: accepted
tags: ["ui", "draft", "mvi"]
---

# The draft keeps one value and one snapshot

Settles #3230 (ownership of `DraftState` vs `DraftUiState.draft`) and the
`save()` half of #3232 (which failures may become a snackbar).

## Context

`DraftMviViewModel` holds the draft twice: once in `DraftState`, once as
`DraftUiState.draft` inside the inherited state flow. A reader reasonably asks
whether the second copy is a leftover, and whether deleting `DraftState` — a
small class used nowhere else — would simplify the editor.

Four facts decide it.

**`DraftState` answers a question the snapshot cannot.** `isDirty` needs a
comparison against the last *explicitly saved* value, which the UI never
observes and must never see: an autosaved draft is still dirty, and `discard()`
must not revert to it. A checkpoint living in `DraftUiState` would be state the
screen can read and argue with, and the ADR
`2026-09-27-draft-mvi-single-state-source.md` already established that editor
state belongs in exactly one place.

**The snapshot is what keeps the state atomic.** `DraftUiState` publishes
`draft`, `error`, `isSaveEnabled`, `isDirty` and `isSaving` together, from one
`updateState`. Deriving them from separate flows is the design that ADR
replaced: it allowed the UI to observe a new draft paired with a stale error.

**Every mutation already funnels through `pushUiState`.** `updateDraft`,
`open`, `discard`, the restore step and `save` all end with it. The copy is
therefore never independently authored — one writer, one direction
(`draftState` → `pushUiState` → `DraftUiState.draft`).

**Deleting the copy was the only alternative worth considering, and it costs
more than it saves.** `DraftUiState` would then have to read the draft from a
second flow at collection time, re-deriving a composite state the ADR above
removed on purpose. That is a rewrite of the state plumbing of two production
editors to delete a 60-line class, with the stale-pairing bug as the price of
being wrong.

## Idea

Name the ownership, keep the design, and fix the one genuine defect the trace
did find: the checkpoint could bless a keystroke that arrived during a save.

## Decision

**`DraftState` owns the draft value; `DraftUiState.draft` is its published
snapshot.** `DraftState` is the only writer of the value; `pushUiState` is the
only writer of the snapshot, and every mutation ends there. No other code may
set either.

**`save()` checkpoints the draft that was persisted, not the draft on screen.**
The editor stays editable while `persist()`, `clearAutosave()` and `onSaved()`
suspend, so a keystroke can arrive at any of them. The old code took the
checkpoint after the write, which marked that keystroke saved: `isDirty` went
false with text on screen that no store held, and the back guard stopped asking
before dropping it. The checkpoint is now the value that was written.

**`onSaved` reports the draft the save should be compared against.**
`2026-11-09-the-saved-draft-is-the-one-the-save-left.md` requires that a
subclass's own adjustment in `onSaved` (NoteEditor clears its dirty flag, because
the text is on disk) does not read as dirty. That adjustment cannot be inferred:
the editor keeps accepting keystrokes while the hook suspends, and inside that
window the hook's edit and the user's are the same event — a revision counter
tried to separate them and could not, which the first review of this change
proved with a failing test. So the hook is handed the draft it was asked to save
and returns the draft the save should be compared against, or null to keep the
persisted value. It never reads the live draft for that.

**`DraftState.revisions` was removed.** It existed for that inference and went
with it: one state value and one checkpoint, no second counter to keep honest.

**Fatal `Error`s escape `save()`; ordinary failures become the error banner.**
Rejected drafts (validation), refusals (`Either.Left`) and thrown `Exception`s
set `DraftUiState.error` and never touch the draft, so a failed save keeps the
user's text. A thrown `Error` propagates to `vmScope`'s
`CoroutineExceptionHandler`, which reports it as `background.coroutine_failed`;
showing a snackbar about an OOM would invite the user to fix a draft no edit can
repair. `finally` still re-enables the button. `CancellationException` always
propagates. This mirrors `runCatchingCancellable`, which already bounds at
`Exception`.

## Rationale

The duplication the issue suspected is a value/snapshot pair, not two owners —
the same shape `MviViewModel` uses for every other ViewModel, for the same
atomicity reason. The cost of the copy is one assignment per mutation; the cost
of removing it is the stale-pairing window and a rewrite of both editors' state
plumbing.

The checkpoint defect was found by asking the question the issue asks — *who
owns this value, and when?* — and it is a real one: the draft was reported
clean while the store lacked the text. It is fixed in the base class, so both
editors get it.

## Consequences

- Both editors keep the shape they have; no editor code changed.
- A keystroke that arrives during a save — while `persist()`, `clearAutosave()`
  or `onSaved()` suspends — leaves the draft dirty, so the back guard still asks
  and `discard()` still reverts to the saved value.
- `onSaved` changed signature: `suspend fun onSaved(saved: D): D?`. Both
  subclasses were updated; a subclass that adjusts the draft must now say so
  instead of relying on the base reading its side effect.
- A fatal `Error` during a save is now handled by the scope's failure policy
  instead of being swallowed into a banner. On Android that path reports and
  keeps the process alive; it does not kill it.
- `DraftState.revisions` is new public surface. It is a counter, not a second
  copy of the value.

## What was not done

- `TaskDetailDraftState` and `ProjectDetailDraftState` were *not* folded into
  `DraftState`. They re-seed from a repository rewrite and carry `Revisioned`
  values; `DraftState` has no repository and no revisions. Merging them would
  add two responsibilities to a class that has one.
- The autosave-triggered write that lands after a successful save or discard is
  still not cancelled. It is pinned as current behaviour in the matrix
  (`a pending autosave rewrites the draft after the save`) and remains the
  open question for #3225, not a simplification.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/DraftState.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/DraftMviViewModel.kt` — `save()`, `pushUiState()`
- `docs/decisions/2026-09-27-draft-mvi-single-state-source.md` — one state source
- `docs/decisions/2026-11-09-the-saved-draft-is-the-one-the-save-left.md` — the second checkpoint
- `shared/src/commonTest/kotlin/com/singularity/todo/core/ui/DraftMviViewModelTest.kt` — the behavior matrix
