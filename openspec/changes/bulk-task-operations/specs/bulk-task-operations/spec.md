# bulk-task-operations

**capability:** `bulk-task-operations` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-BTO-001

The system **SHALL** let a user enter and leave a multi-selection mode on a list of
tasks, and **SHALL** make the current selection visible.

**Rationale:** a selection with no visible state is indistinguishable from a list
that silently changed. The user must be able to see what they are about to act on
before they act on it.

**Test coverage:** to be added with the implementation; a desktop Compose UI test
asserting the selected count is on screen is the minimum.

#### Scenario: Entering selection
- The user long-presses a task
- The task is marked as selected
- The count of selected tasks is visible

#### Scenario: Leaving selection
- The user exits selection mode
- No task remains marked as selected
- The list returns to its ordinary interaction

---

### Requirement: REQ-BTO-002

Selecting a task **SHALL NOT** trigger the action that a normal tap performs, and
**SHALL NOT** trigger a swipe action.

**Rationale:** long-press already carries meaning on these screens — it opens a
context menu. Overloading it silently would remove that. Swipe-to-dismiss shares
the same gesture surface, so a selection gesture that also dismissed rows would
destroy work the user did not intend to destroy.

**Test coverage:** to be added; a UI test that selects and then asserts the task
was neither opened nor removed.

#### Scenario: Long-press selects rather than opens
- The user long-presses a task
- The task is selected
- The task's detail screen does not open
- No context menu appears
- No swipe action is performed

---

### Requirement: REQ-BTO-003

A bulk completion or deletion **SHALL** apply to every selected task as a single
operation: either all selected tasks change, or none do.

**Rationale:** this is the entire reason the bulk operations exist. Completing
three of five tasks when one fails leaves the user with a state they did not ask
for and cannot easily reason about.

**Test coverage:** to be added. This is the requirement the whole change exists to
make reachable, and it is currently untestable through the UI.

#### Scenario: Partial failure leaves nothing changed
- Three tasks are selected for deletion
- The operation fails for one of them
- None of the three are deleted
- The user is told the operation did not complete

---

### Requirement: REQ-BTO-004

A completed bulk operation **SHALL** clear the selection and **SHALL** report what
happened.

**Rationale:** leaving a selection active after acting on it invites a second
unintended action. Silent completion is indistinguishable from a no-op.

#### Scenario: Bulk completion reports
- Three tasks are selected
- The user activates complete
- All three are completed
- The selection is cleared
- A confirmation naming the number of tasks is shown
