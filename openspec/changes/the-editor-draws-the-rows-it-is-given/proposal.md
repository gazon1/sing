# the-editor-draws-the-rows-it-is-given

Issue: — · Related: `docs/decisions/2026-10-21-a-bundle-the-editor-is-given-is-a-row-it-draws.md`

Status: **accepted**
Tags: **task editor checklist attachments rows compose**

## What

The create screen handed the editor a checklist and an attachments bundle, and the
ViewModel behind them could add, tick and remove items, and the draft held them. But
nothing drew a row that opened the sheets: the editor's body draws rows for the
priority, the estimate, the dates, the project, the tags, the recurrence and the pin —
and none for the checklist or the attachments.

So the create flow had a complete checklist feature and nothing the user could press.

Now a bundle the editor is given is a row it draws: the editor draws a checklist row and
an attachments row when it is given the matching bundle, and the row shows how many
items there are and opens the sheet.

## Why

A bundle handed over and not drawn is dead wiring — the code says the feature works
while the user has nothing to press. The audit script does not catch this case, because
the surface *is* wired: to a sheet no row opens.

Two honest states exist, and the editor's answer is the same in both: the row exists, or
the bundle is not there.

## The shape of the fix

- A non-null bundle means a visible row.
- The row shows the count, so the promise the screen makes is kept.
- The checklist is added through the sheet: the detail flow's sections list items and
  tick them off, but have no input, so they cannot add one.
- The detail flow is unaffected: it hands over no bundle, so no row is drawn.

## What not to do

- Do not take a bundle and draw nothing from it. That is the dead wiring this change
  removes.
- Do not draw a row that says "Checklist" when the model holds three items. The count is
  the promise kept.
