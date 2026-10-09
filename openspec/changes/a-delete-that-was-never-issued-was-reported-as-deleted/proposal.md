# Agenda delete/undo and intent routing

## Why

Tapping delete on a task in the agenda produced a snackbar reading `"X" deleted`
and deleted nothing. `AgendaViewModel.handleTaskDelete` never called
`taskRepo.softDelete` — it set the pending marker, emitted an event and armed a
timer. Undo then called `restore()` against a row that was never archived, and
the "deleted" task kept firing reminders.

Notes had the mirror defect: it deferred `repo.delete` until the undo window
expired, so the note lingered for five seconds and then vanished by itself, and
Undo issued a `restore` against a live row.

Neither matched ADR `2026-09-08-task-restore-undo`, which has the delete already
performed — and the notes KDoc claimed to match `AgendaViewModel`, which is how
the false premise propagated into a second feature.

Four screens reached their ViewModels by calling public methods where a sealed
intent was already routed. Two of those paths (`NotesActions.onUndoDelete`,
`TagsIntent.Delete`) had no callers at all.

## What changes

- Agenda deletes for real, cancels reminders first, and reports failures.
- Notes commits the delete on tap and undoes a real deletion.
- The pending-delete marker clears only on a *successful* reversal, so a failed
  undo leaves the offer addressable (`delete-safety-feedback` Phase 1, #78).
- The ViewModel's undo window is the only clock for the snackbar.
- Every screen-triggered mutation goes through the intent dispatcher.
- Agenda selection has one home, in `AgendaUiState.Loaded`.
- Notes selection reaches the rendered state (a second confirmed defect).

## Impact

- Affected specs: `undoable-delete`, `intent-routing` (both new)
- Affected code: `feature/agenda`, `feature/notes`, `feature/tags`
- Supersedes nothing. Corrects the *agenda premise* of `delete-safety-feedback`,
  whose policy work remains valid and unstarted.
- Closes #83 (partly). Updates #78, #80, #104 — all three describe agenda's
  delete-with-undo as working, which it was not.
