# Project Closing — Observable Behavior

**capability:** `projects/project-closing` | **status:** proposed

---

## ADDED Requirements

### Requirement: A project close lands as a whole

Closing a project SHALL close the project's still-open direct tasks together with the
project itself, and the two SHALL land together: either the project and its tasks are
closed, or none of them is.

The close SHALL read only the project's own tasks and the prerequisites of those tasks.

**Rationale:** a cascade that writes several rows with nothing holding them together can
leave the project closed with half its work open — a state that matches neither what
the user asked for nor what the app promised. And a cascade that asks "is this task
blocked" by reading every task the user has answers a question about ten tasks with a
scan of ten thousand.

#### Scenario: Closing a project closes its open tasks

- **Given** a project with an open task and a finished task
- **When** the user closes the project
- **Then** the project is closed
- **And** the open task is closed
- **And** the finished task is untouched

#### Scenario: A blocked task in the project stops the close

- **Given** a project with an open task
- **And** that task has a prerequisite that is still open
- **When** the user closes the project
- **Then** the project is not closed
- **And** the open task is not closed
- **And** the user is asked, and the task in the way is named

#### Scenario: Confirming the request closes the project and its tasks

- **Given** a project with an open task
- **And** that task has a prerequisite that is still open
- **And** the user has been asked
- **When** the user confirms
- **Then** the project is closed
- **And** the open task is closed

#### Scenario: Declining the request leaves the project and its tasks alone

- **Given** a project with an open task
- **And** that task has a prerequisite that is still open
- **And** the user has been asked
- **When** the user declines
- **Then** the project is still open
- **And** the task is still open

### Requirement: A closed project can be opened again

A closed project SHALL be openable again, and opening it SHALL leave its tasks
untouched. A task that belongs to a closed project and is taken back into work SHALL
open that project with it.

**Rationale:** a closed project leaves the project list, but its tasks can still be
picked up in the agenda. Without the rule the task would come back alone and belong
to nothing — the work returned, and the project that holds it stayed invisible.

#### Scenario: A closed project is opened

- **Given** a closed project
- **When** the project is opened again
- **Then** the project is open
- **And** it is in the project list again

#### Scenario: Reopening a task opens its closed project

- **Given** a closed project
- **And** a task that belongs to it
- **When** the task is taken back into work
- **Then** the project is open again

#### Scenario: A task without a project is unaffected

- **Given** a task that belongs to no project
- **When** it is taken back into work
- **Then** no project changes
