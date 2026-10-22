# Project Closing — Observable Behavior

**capability:** `projects/project-closing` | **status:** proposed

---

## ADDED Requirements

### Requirement: A project can be closed, and closing it is distinct from archiving and from deleting

The system SHALL offer to close a project, and SHALL record that the project is
closed.

A closed project SHALL be distinct from an archived project and from a deleted
one: a closed project is still there, still readable, and is not in the bin. A
closed project SHALL NOT appear in the list of projects.

Closing a project SHALL NOT delete it, and SHALL NOT archive it.

**Rationale:** the project list exists to show what is being worked on. A
project whose work is finished has no place in that list, but it is not deleted
and it is not in the bin either — the two are different facts about a project,
and one flag cannot carry both. Reusing the existing deletion flag would have
put closed projects in the bin and made "closed" and "deleted" the same word.

#### Scenario: Closing a project

- **Given** a project that is open
- **When** the user closes it
- **Then** the project is closed
- **And** it no longer appears in the list of projects
- **And** it is still readable

#### Scenario: A closed project is not a deleted project

- **Given** a project that has been closed
- **When** the user looks for it among the deleted projects
- **Then** it is not there

#### Scenario: A closed project is not an archived project

- **Given** a project that has been closed
- **When** the user looks for it among the archived projects
- **Then** it is not there

### Requirement: Closing a project closes the tasks that are still open in it

Closing a project SHALL close the tasks of that project that are still open, and
SHALL leave alone the tasks of the project that are already closed.

Closing a project SHALL NOT close the tasks of any other project, and SHALL NOT
close the tasks of a project nested inside it.

Where a task of the project repeats, closing the project SHALL close it without
arranging a further occurrence.

**Rationale:** "this project is done" that leaves its tasks live in the agenda
is a promise the system does not keep — the work would come back as today's
tasks tomorrow. The nested case is separate: a nested project is a position in
a hierarchy, not something the parent contains, and a parent being finished
does not mean the child project is.

#### Scenario: The open tasks of the project are closed

- **Given** a project with two tasks, one open and one already closed
- **When** the user closes the project
- **Then** the open task is closed
- **And** the already-closed task is unchanged

#### Scenario: Another project's tasks are left alone

- **Given** a project with an open task
- **And** another project with an open task
- **When** the user closes the first project
- **Then** the other project's task is still open

#### Scenario: A nested project's tasks are left alone

- **Given** a project with an open task
- **And** a project nested inside it with an open task
- **When** the user closes the outer project
- **Then** the nested project's task is still open

#### Scenario: A repeating task gets no further occurrence

- **Given** a project with a repeating task that is still open
- **When** the user closes the project
- **Then** the task is closed
- **And** no further occurrence of it is arranged

### Requirement: Closing a project whose task is blocked asks first, and writes nothing until it is confirmed

When one of the project's still-open tasks has an unfinished prerequisite,
closing the project SHALL NOT write anything: neither the tasks nor the project
SHALL be closed. The system SHALL ask, naming the tasks that are in the way.

Confirming the request SHALL close the project and its still-open tasks.
Declining the request SHALL leave the project and its tasks exactly as they
were.

The confirmation SHALL state how many still-open tasks the close will close.

**Rationale:** the same rule that governs closing one task governs closing a
set of them. A confirmation that arrives after half the work is already done
is not a confirmation — it is a report, and the user can no longer act on it.
So the check happens before anything is written, the way the existing bulk
completion validates every item before it changes any of them.

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

#### Scenario: The confirmation says what the close will close

- **Given** a project with three tasks that are still open
- **When** the user asks to close the project
- **Then** the confirmation says that three tasks will be closed
