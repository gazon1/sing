# delete-affordances

## ADDED Requirements

### Requirement: REQ-1 Every destructive action states its consequence and offers a way back

Every action that deletes user data SHALL be classified as reversible or
irreversible, and SHALL present the affordance its class requires:

- a **reversible** delete SHALL emit `Notification.Undo`;
- an **irreversible** delete SHALL ask for confirmation before acting.

No list screen SHALL delete user data with neither affordance. A delete that
cannot be undone and is not confirmed is a data-loss path with no gate.

The classification SHALL be decided per action rather than per screen, and the
implementation SHALL reuse the pattern already in the tree rather than introduce
a new one. `TagGroupsScreen.kt` is the reference for an irreversible cascade;
`TaskDetailContent.kt` for a reversible delete.

**Rationale:** the policy was decided and applied in some places and not others.
`NotesListScreen.kt`, `TagsScreen.kt` and `ProjectsScreen.kt` each contain zero
`Notification.Undo` and zero `ConfirmActionDialog` — verified, not assumed. The
inconsistency is invisible because each screen is individually plausible: a list
with a delete swipe that simply deletes looks like every other app.

#### Scenario: A reversible delete happens

- **Given** an action whose effect can be reversed
- **When** the user completes it
- **Then** an undo affordance is shown
- **And** no confirmation was requested, because the user is not at risk

#### Scenario: An irreversible delete is requested

- **Given** an action whose effect cannot be reversed
- **When** the user requests it
- **Then** a confirmation is shown before anything is written
- **And** dismissing the confirmation leaves the data untouched

#### Scenario: A new list screen is added

- **Given** a screen that can delete user data
- **When** the screen is added
- **Then** a check reports any delete path with neither affordance
- **And** the check names the file and the action, so the omission is not
      discovered by a user

#### Scenario: A bulk delete spans two classes

- **Given** a multi-select delete where some selected items are reversible and
      some are not
- **When** the user confirms
- **Then** the confirmation states which is which
- **And** it does not present one summary dialog that hides the difference
