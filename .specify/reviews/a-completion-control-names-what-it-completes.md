---
change: a-completion-control-names-what-it-completes
verdict: pass
date: 2026-11-11
pr: 2824
reviewed: 58a0db7c4d83a3b8d5db023e07f8bb36694c00db
documents: sha256:324e540b82f1a6cc52ff18fb06ffd15d3e2ea72dc5c55228564e38a048c41094
---

## What was reviewed

The change `a-completion-control-names-what-it-completes` — its contract documents, as
the digest above records them — against the code they describe: the completion control
on a task row in a list, the toggle on a task card, and the shared row that renders
either a subtask or a checklist item.

The reviewer was a separate agent given the branch's diff, the ADR, the OpenSpec change
and the test, and nothing else. It read the Compose semantics sources on disk to
settle the modifier-order question and cross-checked the three call sites, the locale
files and the test's harness conventions.

`reviewed:` is the head of the branch as the review judged it. The contract has not
moved since — the two fixes that followed changed code, not the proposal or the spec
delta — so the digest is the one the gate recomputes.

## What the review found

The verdict was **pass**: no blocking defect. The three controls each expose a
non-empty localized name, the state never enters the name, the card toggle's
hardcoded English `"Toggle complete"` is gone, and no existing call site of the
touched widgets is broken by the new `kind`.

Two should-fix items and one note:

1. **Should-fix — the checklist control had no `stateDescription`.** The Material3
   `Checkbox` supplies the role and the toggle state, but the row's own semantics
   block added no state description. A user therefore heard "Item: Buy milk, not
   checked" on a checklist row and "Task: Buy milk, not done" on a task row — the same
   state, worded two ways, on two screens. The ADR claimed the state is carried by the
   state description on every control, and that was not literally true of one of the
   three it governs.

2. **Should-fix — the checklist test asserted only the name.** It read
   `ContentDescription` and nothing else, so it could not fail on a control that lost
   its checkbox role or its state — while the spec scenario for the checklist item
   demands that the announced state says whether the item is done. Nothing in the
   suite pinned that.

3. **Note — two rows with the same title are named the same.** "Task: Buy milk" twice
   on one screen is one name twice. The format was chosen deliberately, and the
   ambiguity is a wording question for the next change, not a regression of this one.

The reviewer also confirmed, from the Compose sources and from the green run, the
point the change rests on: the caller's outer `semantics` block wins over the
`Role.Button` that `IconButton` sets for itself, so the card toggle really is
announced as a checkbox, and the glyph's `contentDescription = null` leaves exactly
one name on the button.

## What was done about it

Both findings are fixed in the same branch, in the commit after the reviewed one:

- The checklist row's semantics block now sets `stateDescription` from the same two
  strings the other two controls use, so all three announce "Done" and "Not done".
- The checklist test now asserts the role and the state description of both the
  subtask row and the checklist-item row, alongside the names.

The ADR now says what the code does: the state is a state description on all three
controls, with the same wording on each.

The rule has a check that can fail: `TaskCompletionSemanticsTest` asserts the
description's *value* — a test tag alone would satisfy a presence check while leaving
the control nameless for a screen reader — and now asserts the role and the state of
the control that carries the shared row.

## Links

- ADR `docs/decisions/2026-11-11-a-completion-control-names-what-it-completes.md`
- OpenSpec change `openspec/changes/a-completion-control-names-what-it-completes/`
- Test `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/TaskCompletionSemanticsTest.kt`
- Issue <https://github.com/gazon1/sing/issues/2753>
