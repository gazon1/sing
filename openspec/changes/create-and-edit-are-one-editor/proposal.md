# create-and-edit-are-one-editor

Issue: #2882. Tracker: #2872. Decision: `2026-11-14-create-and-edit-are-one-editor`.

## What

Make the create screen and the task detail expose one field contract: the same
attribute rows, in the same order, on both — and stop a pristine create form from
opening with a validation error.

Concretely:

- The create screen gains the **estimate** row it lacked, and the write from a
  draft carries the estimate.
- The detail gains the **start-date** row it always hid; the write path already
  existed.
- The **pin** reaches the created task. It was drawn, toggled and then dropped by
  the write path.
- The title error on the create screen appears after an edit or a refused save,
  not on the first frame.
- The multi-date date sheet opens expanded, so its confirm row is on the screen
  and reachable. It opened half-expanded, which put the row below the screen edge
  on a 360×800 phone — the sheet could not be committed at all.

## Why

The research in #2871 measured the divergence (`UXF-05`, `UXF-12`, `UXF-24`):
the two screens taught two sets of rules for one object, and Create could not
express a task the detail could. Users had to create a lesser task and reopen it
— the round trip the task editor refactor set out to remove.

Two of the gaps were not gaps in the call site at all. The pin and the estimate
were offered by the editor and dropped by the write path, so the rows accepted
input and changed nothing. No test read the created task's pin, which is why
nothing failed.

## How

Field parity is a call-site change: the renderer already supports every row, and
the create screen simply did not pass the bundles. The write-path defect is one
mapping in the domain: the validated input is copied into the task field by
field, and two fields were not on the list.

Validation is one line at the screen: both surfaces read the message from the
state the ViewModel already recomputes, so "after interaction" has one owner.

The parity claim is held by an ordered comparison of the rows the two surfaces
draw, with the deliberately one-sided rows listed beside it and their reasons.

## Out of scope

- **Sharing a ViewModel or a save lifecycle.** The parent tracker forbids it and
  this change does not approach it: Create keeps its draft and explicit save, the
  detail keeps its debounced autosave.
- **The reminder, deadline and workflow-state rows on Create.** They wait on the
  scheduling and empty-section contracts (#2876, #2880), which own where those
  facts live on a task that does not exist yet.
- **Landing on the new task's detail after save.** The research proposed it; the
  product decision kept the list, and this change does not reopen it.
- **Localizing the editor's labels.** `UXF-21` / #2882's sibling work owns it.
