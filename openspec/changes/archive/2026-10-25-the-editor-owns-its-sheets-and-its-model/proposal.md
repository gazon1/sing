# the-editor-owns-its-sheets-and-its-model

Issue: — · Related: `docs/decisions/2026-10-21-the-editor-owns-its-sheets-and-its-model.md`

Status: **accepted**
Tags: **task editor sheets state compose**

## What

The task editor had two sheet states and two models.

The create screen opened its sheets through a state of its own. The editor rendered the
sheets from a state of its own. The two were never the same object, so a tap on the
priority row in the create flow opened a sheet nobody was watching: the row did nothing,
and nothing said so.

The editor also built a **second** model and a **second** callback bundle for the sheets,
from the values it had already unpacked. That model was empty where it mattered — a task
id of `null` for a task that exists, an empty checklist for a draft that holds items — and
the second bundle answered the sheets with no-ops.

Now there is one state and one model:

- The state the caller's rows open a sheet through is the state the sheets are rendered
  from. One state, held by the caller, read by the editor.
- The sheets are rendered from the model and the callback bundle the editor was given.
  There is no second model, and no second bundle.

## Why

Two states that are supposed to be one state are the same mistake as a value and a counter
held in two fields: the pair can lie, and the lie is silent. The create flow's rows were
inert — every attribute row in the create editor did nothing when tapped, and the screens
looked perfectly fine.

The second model was the same mistake in a different place: the editor answered the sheets
from values it no longer had. A sheet that loads a checklist by task id was handed `null`
for a task that exists.

## The shape of the fix

- The editor resolves the sheet state once: the caller's state when there is one, its own
  otherwise.
- The rows open sheets through that state; the host renders from that state.
- The host takes the model and the callback bundle the editor was given.
- The exploded editor body — the one with forty parameters — stops being public API: only
  the scaffold and the previews use it.

## What not to do

- Do not give the editor a second state "just in case". Two states that must agree are
  two states that will not.
- Do not rebuild the model for the sheets. The editor has the model; a sheet that needs
  the task id must be told the task id.
- Do not open a sheet that no row can reach. That is a separate question, and this change
  does not answer it.
