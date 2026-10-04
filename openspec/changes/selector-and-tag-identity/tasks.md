# Tasks — selector-and-tag-identity

## Test tags (#109, #90)

- [ ] Give `TaskContextMenuSheet.SheetRow` a stable action id, the way
      `TaskDetailViewScreen.kt:566-584` already does with
      `TestTags.EditorOverflow.*`. The constant is the identity; the label stays
      display-only.
- [ ] Add `action` to `TaskEditorCallbacks.TaskEditorMenuItem` rather than
      overloading `testTag` — `testTag: String? = null` is nullable precisely so a
      row can have no tag, and that is how `EditorOverflow.PIN` / `UNPIN` ended
      up declared and never applied.
- [ ] Add the static check rejecting `testTag(TestTags.*(…label…))` and the same
      shape over `.text` / `.title`.
- [ ] Write the check's **positive control** first: a synthetic
      `testTag(TestTags.taskAction(label))` must fail the check. A check that
      has only ever seen clean files has not been shown to work, and this one
      gates a build.
- [ ] Sweep the other menu and sheet call sites for the same shape, and record
      the count found — the sweep is the deliverable, not just the one file.
- [ ] Keep #90's flow sweep separate and link the two. #90 treats the flows that
      already exist; this removes the cause so the next flow does not inherit it.

## Selector reachability (#107)

- [ ] Decide first: add `ByRegexp` and `ByDateRange` templates, or declare both
      engine/preset-only. Record the decision before implementing, because the two
      need different controls — a text field and a from/to pair — and neither is
      `MultiSelectSheet`.
- [ ] If adding them: build the controls the parameters actually imply. A
      multi-select over values that do not exist is a worse outcome than no
      option.
- [ ] If declaring them: put the declaration in the template registry, not in a
      separate document, so a future variant without either fails to compile.
- [ ] Update the editor's own copy to match. A user who cannot configure a type
      should be told, in the place they are looking.
- [ ] Correct the backlog entry, which records all five selectors as reachable
      from the editor. Three are. The entry is the thing a future reader trusts,
      so leaving the overstatement in place is the defect.

## Test clocks (#108)

- [ ] Pass an explicit `clock` in `CalendarFlowTest.kt:38,48,66`. The harness
      already binds it last in the Koin module list and
      `AgendaBadgePolicyFlowTest.kt:76` shows the call shape.
- [ ] Sweep the remaining desktop flows for date-dependent assertions, and
      record how many still run on the system clock. One is a defect; thirty is
      a policy question about what the desktop suite is for.
- [ ] Assert the month and week boundaries against a fixed date chosen so the
      assertions are non-trivial — not the first of a month, where an
      off-by-one passes.
- [ ] Keep #42 separate. It is the KDoc claim that test sources are exempt from
      `NoDirectClockSystem`; closing this does not close that, and the two are
      easy to conflate because the entry text points at the other.
