---
title: "The recurrence sheet commits its own rule"
date: 2026-10-21
status: accepted
tags: [architecture, tasks, compose]
---

# The recurrence sheet commits its own rule

## Context

The task editor hosts its sheets in one place: `TaskEditorSheetsHost` picks the active
sheet and renders it inside `TaskEditorSheetHost`, which provides the chrome — the
title, the ×, and an optional ✓.

The recurrence sheet was the odd one out. `TaskEditorSheetsHost` wrapped
`RecurrencePickerSheet` in that host — and the picker rendered **another**
`TaskEditorSheetHost` of its own, inside the first one:

```kotlin
// TaskEditorSheetsHost.kt
is TaskEditorSheet.Recurrence -> TaskEditorSheetHost(
    title = "Repeat",
    onClose = onSheetDismiss,
    testTagConfirm = TestTags.SHEET_CONFIRM,
) {
    RecurrencePickerSheet(...)      // …which renders a TaskEditorSheetHost of its own
}
```

Three things came out of that:

- **The sheet had two headers and two windows.** A `ModalBottomSheet` inside a
  `ModalBottomSheet` — on desktop, two more semantics roots; on Android, a sheet inside
  a sheet.
- **The ✓ was in the wrong place.** The outer host was given `testTagConfirm` but no
  `onConfirm`, so it rendered **no ✓ at all** — the parameter was a no-op. The picker's
  own ✓ did the committing and carried no tag.
- **`sheet_confirm` did not exist.** The tag was declared in `TestTags`, listed in
  `Maestro/TAGS.md`, and tapped by `Maestro/flows/tasks/17-create-daily-recurring.yaml`
  — and no element ever carried it. A flow step that could not pass, and a registry
  entry that named nothing.

The picker is not like the other sheets, and that is why it brought its own chrome. The
other sheets apply their choice the moment it is made — pick a priority, the priority is
set and the sheet closes. The recurrence rule is a **pending choice**: the user picks a
type and a base, and the ✓ is what turns that choice into a rule. The commit button
belongs to the thing that owns the pending choice.

## Idea

The sheet that owns a pending choice owns its commit button — and the button that
commits carries the tag that says so.

## Decision

**The recurrence picker brings its own chrome, and its ✓ is tagged `sheet_confirm`.**

- `TaskEditorSheetsHost` renders `RecurrencePickerSheet` directly, the way it renders
  the date and time pickers — no wrapper host around it. The sheet has one header, one
  window, and one ✓.
- The picker's own host passes `testTagConfirm = TestTags.SHEET_CONFIRM`, so the button
  that commits the rule is addressable by id — on the device and from a JVM test.
- The outer host's `testTagConfirm = TestTags.SHEET_CONFIRM` — the no-op — is gone.

## Rationale

- **A tag that names nothing is worse than no tag.** `sheet_confirm` was in the
  registry, in `TAGS.md`, and in a flow. Every one of those places said the button
  existed. Nothing said otherwise, and the flow's step could not pass.
- **The commit button belongs to the pending choice.** Moving the ✓ to the sheet that
  holds the pending choice keeps the choice and its commit together: the outer host's
  ✓ would have to apply a rule it cannot see.
- **One header, one window.** The nesting was not a design; it was a picker written as
  a standalone sheet and later put inside the editor's host without taking its chrome
  out. Removing the wrapper restores what the picker was written to be.
- **This is the direction issue #812 asks for.** The gate that checks UI-automation
  selectors collects the file that *opens the window*, because the mapping of testTags
  to resource-ids happens there and is not inherited across windows. A picker that opens
  its own window maps its own tags — which is what makes `recurrence_option_daily`
  addressable by id on the device.

## Consequences

- The recurrence sheet looks different: one header instead of two, and one window
  instead of a sheet inside a sheet. The title, the × and the ✓ are the same.
- `sheet_confirm` exists now. The Maestro flow's step works, and a JVM test can press
  the same button.
- The desktop tier can carry the choice: `TaskRecurrenceScenarioTest` opens the
  picker, chooses Daily, commits it, and asserts that the row reports the rule. The
  claim that "a `ModalBottomSheet` is unreachable from a JVM test" was measured on a
  broken row and is not a fact about the platform.
- The recurrence sheet's tags are mapped by the file that opens its window, which is
  what the selector gate collects. The other seven sheets of issue #812 are untouched.

## Links

- `2026-10-21-the-editor-owns-its-sheets-and-its-model` — one state for the editor's
  sheets, and the sheet that renders them.
- `2026-10-21-the-popups-open-their-own-windows-too` — the measurement that a popup's
  content is reachable from the JVM tier.
- Issue #812 — the selector gate and the eight sheets that lose their tags.
- Issue #874 — `recurrence_option_daily` was on the screen but not visible.
