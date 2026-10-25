# the-recurrence-sheet-commits-its-own-rule

Issue: — · Related: `docs/decisions/2026-10-21-the-recurrence-sheet-commits-its-own-rule.md`

Status: **accepted**
Tags: **task editor recurrence sheet compose**

## What

The task editor's recurrence sheet was a sheet inside a sheet: the editor's host wrapped
the picker in a generic sheet host, and the picker rendered a sheet host of its own
inside that one. The user saw two headers, and the commit button that turned a pending
choice into a rule belonged to neither of them in a way anything could address.

The consequence that mattered: `sheet_confirm` was declared in the tag registry, listed
in `Maestro/TAGS.md`, and tapped by the Maestro flow for the daily-recurring scenario —
and **no element ever carried it**. The flow's step could not pass, and nothing in the
build said so.

Now the picker brings its own chrome, as it was written to: one header, one window, and
a ✓ that commits the rule and carries the tag that says so.

## Why

A sheet that holds a pending choice owns the control that commits it. The other editor
sheets apply their choice the moment it is made — pick a priority, the priority is set
and the sheet closes. The recurrence rule is not like that: the user picks a type and a
base, and the rule exists only once the choice is confirmed. The commit control belongs
to the thing that holds the pending choice, and it has to be addressable — by id on the
device, by tag from a JVM test.

The nesting was not a design. It was a picker written as a standalone sheet and later put
inside the editor's host without taking its chrome out.

## The shape of the fix

- The editor's host renders the picker directly, the way it renders the date and time
  pickers.
- The picker's own ✓ carries `sheet_confirm`.
- The desktop tier can therefore carry the choice: the scenario test opens the picker,
  chooses Daily, commits it, and asserts that the editor's row reports the rule.

## What not to do

- Do not render the sheet host around a sheet that brings its own. Two headers and two
  windows are not a design.
- Do not declare a tag for a control that no element carries. `sheet_confirm` was in the
  registry, in `TAGS.md` and in a flow, and none of them were true.
- Do not leave the commit control unaddressable. A flow that taps a button the app does
  not render is a flow that fails for a reason nobody can see.
