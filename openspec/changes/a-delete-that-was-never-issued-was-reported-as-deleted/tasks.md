## 1. Agenda delete reports a delete that happened

- [x] Add `AgendaIntent.UndoDeleteTapped` and `AgendaUiEvent.ShowError`
- [x] Cancel reminders before deleting; abort the delete if cancellation fails
- [x] Call `taskRepo.softDelete` and offer undo only on success
- [x] Clear the pending marker only on a successful reversal
- [x] Guard the undo slot: a generation token for the timer, a request sequence
      for completion order (`Dispatchers.Default` is multi-threaded)

## 2. The undo window is the only clock

- [x] Present the snackbar `Indefinite`, dismiss when the marker clears
- [x] Drop `AgendaUiEvent.UndoDelete` — the marker carries the same id and title,
      and the screen collected the event into an empty branch

## 3. Selection has one home

- [x] Agenda: remove the duplicate flows and `updateSelectionState()`
- [x] Reconcile selection through CAS `updateState`, pruning ids that have left
      the evaluated sections
- [x] Notes: selection handlers must write the state the screen reads

## 4. Intent routing

- [x] Notes: route undo and create through `NotesIntent`; add
      `NotesActions.onCreateNote`
- [x] Tags: dispatch `Delete` through `TagsIntent.Delete`
- [ ] **Open decision (plan §10).** `AgendaUiEvent.ShowError` now exists and the delete
      path reports through it. Routing `toggleComplete` / `togglePinned` through it as
      well would fully close #132, but that changes what the user sees on a checkbox tap,
      which is a product call, not a correctness one. Not done pending a decision.

## 5. Tests

- [x] Agenda: delete, undo, reversal failure, window expiry, supersession,
      reminder cancellation
- [x] Agenda: prove the supersession test is non-vacuous
- [x] Notes: rewrite the three tests that encoded deferred delete
- [x] Notes: selection-while-idle diagnostic
- [x] Tags: `TagsViewModelTest`; retire the allowlist entry

## 6. Docs

- [x] Correct the `AgendaViewModel` description in two house skills
- [x] File the issue whose premise the agenda defect invalidated (#253), and flag the
      invalidated premise on #78, #80, #104
- [x] Correct `deferred-backlog.md#delete-without-confirm-or-undo`, which recorded the
      agenda flow as a working reference
- [x] ADR (`2026-10-09-undoable-delete-contract`)