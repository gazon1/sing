# Tasks — Observable Behavior

**capability:** `tasks` | **status:** proposed

---

## ADDED Requirements

### Requirement: A recurring close is refused while a prerequisite is open

Completing one occurrence of a recurring task SHALL be refused while one of the
prerequisites that block it is still open. The refusal SHALL be the write
itself, and it SHALL leave the series unchanged: the occurrence is not closed,
the series is not moved to its next date, and no copy of a missed occurrence is
written.

When the close of an occurrence is refused, the app SHALL ask the user, naming
the prerequisites that are in the way, and SHALL write nothing until the user
confirms. On confirmation the occurrence SHALL be closed and the series SHALL
move forward, even though the prerequisite is still open.

#### Scenario: A recurring close is refused while a prerequisite is open

- **Given** a recurring task with a prerequisite that blocks it
- **And** that prerequisite is still open
- **When** the user asks for the occurrence to be closed
- **Then** the occurrence is not closed
- **And** the series is not moved forward
- **And** the user is asked, and the prerequisites in the way are named

#### Scenario: Confirming the refusal closes the occurrence and moves the series

- **Given** a recurring task with an open prerequisite that blocks it
- **And** the user was asked and confirmed
- **When** the occurrence is closed
- **Then** the occurrence is closed
- **And** the series is moved to its next due date

#### Scenario: A refused recurring close writes no copy of a missed occurrence

- **Given** a recurring task in catch-up mode with a prerequisite that blocks it
- **And** that prerequisite is still open
- **When** the user asks for the occurrence to be closed
- **Then** no copy of a missed occurrence is written
- **And** the series still holds the occurrence that was asked about

### Requirement: A bulk close asks the same question a single close asks

Closing a selection of tasks SHALL be refused when one of the tasks in it has a
prerequisite that is still open. The refusal SHALL close nothing: no task in
the selection is closed, and the selection remains selected.

When a bulk close is refused, the app SHALL ask the user, naming the tasks in
the selection that are in the way, and SHALL write nothing until the user
confirms. On confirmation every task in the selection SHALL be closed, including
the ones whose prerequisite is still open.

#### Scenario: A blocked bulk close asks and writes nothing

- **Given** a selection of tasks, one of which has a prerequisite that is still
  open
- **When** the user asks for the selection to be closed
- **Then** no task in the selection is closed
- **And** the user is asked, and the tasks in the way are named
- **And** the selection is still selected

#### Scenario: Confirming the refusal closes the whole selection

- **Given** a selection of tasks, one of which has an open prerequisite that
  blocks it
- **And** the user was asked and confirmed
- **When** the selection is closed
- **Then** every task in the selection is closed
