# Tasks — a-completion-control-names-what-it-completes

## The shared source

- [x] shared/ — Give the task family's completion controls one shared source of
      the accessible name, mapping the kind of the thing (task, subtask,
      checklist item) to its localized wording, with the thing's title appended.
- [x] shared/ — Fall back to the existing untitled string when the title is
      empty, so the name is never empty and never ends in a dangling separator.
- [x] shared/ — Keep the wording in the string resources, in every locale.

## The controls

- [x] shared/ — Name the completion control on a task row, and give it the
      task's own title and the task kind.
- [x] shared/ — Name the completion control on a task card, give it the task's
      own title and the task kind, expose the checkbox role, and carry the
      completion state as a state rather than in the name.
- [x] shared/ — Name the completion control on the shared row that renders a
      subtask or a checklist item, with the kind decided by the caller, so the
      two are announced apart.
- [x] shared/ — Drop the glyph's own description from the card's control, so
      exactly one name lives on it.

## The suite

- [x] desktopApp/ — Assert the accessible name's *value* on each of the three
      controls, the checkbox role, the completion state, and that activating the
      control toggles completion.
- [x] desktopApp/ — Assert that a subtask row and a checklist item row are
      announced apart, and that activating the control does not open the thing.
- [x] desktopApp/ — Verify with `TaskCompletionSemanticsTest`.

## The record

- [x] docs/decisions/ — Record the decision that the name carries the kind and
      the title, and the state stays a state.
- [x] openspec/ — Record the requirement in the tasks spec.
