# genui — Observable Behavior

**capability:** `genui` | **status:** proposed

> Modification of the `genui` capability: one requirement is added. The requirement already in the
> capability — that the count of the replacements that came from outside travels inside the value
> it counts — is unchanged.

---

## ADDED Requirements

### Requirement: REQ-GENUI-MODAL-1

A generated surface SHALL show the content of a modal only as a dialog, and only while the
data-model path the modal names holds true. While that path does not hold true, the modal's
content SHALL NOT be drawn at all.

Dismissing the dialog SHALL write false to that path, so that the path holds the whole of the
dialog's state and a later message writing true to it opens the dialog again.

#### Scenario: A modal whose path is true
- A modal names a path, and the surface's data model holds true at it
- The surface is drawn
- The modal's content is shown in a dialog over the surface, and not in the surface's flow

#### Scenario: A modal whose path is false
- A modal names a path, and the surface's data model holds false at it
- The surface is drawn
- The modal's content is not drawn at all

#### Scenario: A modal whose path holds nothing
- A modal names a path, and the surface's data model holds nothing at it
- The surface is drawn
- The modal's content is not drawn at all

#### Scenario: The dialog is dismissed
- A modal's dialog is open, and the user dismisses it
- The dialog closes, and the surface's data model holds false at the path the modal named

#### Scenario: The path is true again
- A modal's dialog was dismissed, and a later message writes true to the path it named
- The surface is drawn
- The modal's content is shown in a dialog again
