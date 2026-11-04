# genui-modal-dialog

**Status:** proposed · **Spec:** `genui` (modified)

## What

A modal in a generated surface SHALL be a dialog, and it SHALL be open only while the data-model
path it names holds true.

## Why

The catalogue tells the model that a modal shows while its path is true, and the client drew the
modal's content inline whatever the path held. The declaration was a promise the renderer did not
keep: a model that sent a modal got its content pasted into the surface whether it had asked for a
dialog or not, and the path it had bound was read by nothing at all.

The path is also the right place for the flag. It is where a model's own update lands, so a
message that writes true can open the dialog, and a dismissal that writes false can close it —
one value, in one place, that both the model and the user can reach.

## Why not

Removing the component was the other way out, and it was not taken. A modal is the only component
in the catalogue with a placement restriction — it may sit only inside a card, a column or a row,
because a modal inside a list row opens a surface the user cannot get out of — and that
restriction is worth its cost only if the thing it restricts is a modal.

## Scope

- The modal's rendering: the content is drawn in a dialog, and nothing is drawn while the path
  does not hold true.
- Dismissing the dialog writes false to the path, so the path holds the whole of the dialog's
  state.
- The description the model reads, which no longer calls the modal inline.
- Out of scope: the placement restriction, which is unchanged; the way a surface is created and
  updated; every other component.
