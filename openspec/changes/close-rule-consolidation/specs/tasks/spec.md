# Tasks — Observable Behavior

**capability:** `tasks` | **status:** proposed

---

## ADDED Requirements

### Requirement: A completion is refused while a prerequisite is open

Completing a task SHALL be refused while one of the prerequisites that block it is
still open. The refusal SHALL be the write itself: the same operation that completes
the task SHALL decide whether it may, so that no separate check can be out of date when
the write lands.

When a completion is refused, the app SHALL ask the user, naming the prerequisites that
are in the way, and SHALL write nothing until the user confirms. On confirmation the
task SHALL be closed even though the prerequisite is still open.

Taking a finished task back into work SHALL never be refused.

#### Scenario: A close is refused while a prerequisite is open

- **Given** a task with a prerequisite that blocks it
- **And** that prerequisite is still open
- **When** the user asks for the task to be closed
- **Then** the task is not closed
- **And** the user is asked, and the prerequisites in the way are named

#### Scenario: Confirming the refusal closes the task

- **Given** a task with an open prerequisite that blocks it
- **And** the user was asked and confirmed
- **When** the task is closed
- **Then** the task is closed

#### Scenario: Taking a task back into work is never asked about

- **Given** a finished task
- **When** the user takes it back into work
- **Then** it is open again, and no question is asked

### Requirement: A bulk close asks too

Closing several tasks at once SHALL be refused when one of them has an open
prerequisite that blocks it. The app SHALL report which task is in the way and SHALL
close none of the selected tasks.

#### Scenario: A selection with a blocked task closes nothing

- **Given** a selection of tasks
- **And** one of them has an open prerequisite that blocks it
- **When** the user closes the selection
- **Then** none of the selected tasks is closed
- **And** the task in the way is named

#### Scenario: A selection without a blocked task is closed

- **Given** a selection of tasks
- **And** none of them has an open prerequisite that blocks it
- **When** the user closes the selection
- **Then** every selected task is closed

### Requirement: A task that comes back to work brings its project with it

When a task that belongs to a closed project is taken back into work, the project SHALL
be opened as well, so that the task does not belong to a project the user cannot see.

#### Scenario: Reopening a task opens its closed project

- **Given** a closed project
- **And** a task that belongs to it
- **When** the task is taken back into work
- **Then** the project is open again

#### Scenario: A task without a project is unaffected

- **Given** a task that belongs to no project
- **When** it is taken back into work
- **Then** no project changes
