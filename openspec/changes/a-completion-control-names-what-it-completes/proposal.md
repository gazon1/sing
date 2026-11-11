# a-completion-control-names-what-it-completes

Issues: #2753 · Backlog entries: none

## What

Every completion control in the task family — the one on a task row in a list,
the one on a task card, and the one on a shared row that renders either a
subtask or a checklist item — carries an accessible name that says which thing
it completes.

The name is the kind of the thing and the thing's own title: `Task: Buy milk`,
`Subtask: Buy milk`, `Item: Buy milk`. The name is localized through the
existing string resources.

The completion state stays where it belongs: a semantic state on the control,
separate from its name. A control's name is the same whether the thing is done
or not, and the state says which it is.

## Why

A screen reader user met a control that said nothing about what it completed. The
list checkbox had a role and a checked state but no name; the card toggle had a
generic description that named no task; the shared checklist row had a role and
a state but no name either.

On a list of twenty tasks every checkbox announced the same thing. The user
could not tell one from another without walking the surrounding text, which is
the work the accessible name exists to do.

The card toggle's description was also hardcoded English, so it was not
localized at all — a Russian user heard an English word for a control that sits
among Russian ones.

The issue text proposes `Complete task: <title>`. The action belongs to the
role, not to the name: the control already says "toggle this thing's
completion", and a name that repeats the action goes stale the moment the
control is a toggle rather than a one-way action. Splitting the name from the
state also lets one name serve both states, so the user hears the same thing on
every visit and hears the state change separately.

## How

- The kind travels with the row, not with the widget. The shared checklist row
  renders a subtask in one place and a checklist item in another, and only the
  caller knows which.
- One shared helper owns the mapping from kind to localized name, so the three
  controls cannot drift into three wordings. A title that is blank falls back
  to the existing untitled string rather than producing a dangling colon.
- The name lives on the control itself. A test tag, a sibling label, or the
  visible text elsewhere in the row is not a substitute — the name has to
  travel with the node the user activates.
- The card toggle keeps its existing look. It gains the checkbox role and the
  state description; the glyph's own description is dropped so that exactly one
  name lives on the button.
- The suite asserts the name's *value*, not just that a name is present, because
  a test tag alone would satisfy a presence check while telling the user
  nothing.

## Out of scope

- The completion button in the task detail editor. It is a third control and
  gets its own change.
- The other controls that carry hardcoded English descriptions elsewhere in the
  app. This change names the task family's completion controls and does not
  sweep the rest.
- The manual screen-reader pass on a device. The change says what the controls
  expose; that a screen reader speaks them is confirmed by hand.
- Consolidating the task family's two checkbox widgets into one.
