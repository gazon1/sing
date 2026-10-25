# projects/project-closing Specification

## Purpose
Closing a project, which is distinct from archiving it and from deleting
it: closing takes the project's still-open tasks with it, asks first when
one of them is blocked, lands as a whole, and leaves a closed project
reachable and reopenable.

## Requirements

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

### Requirement: A closed project stays reachable

A closed project SHALL remain reachable by the user. The projects screen SHALL
list the projects the user closed, most recently closed first, and SHALL offer
an action that opens a closed project again.

Opening a closed project SHALL put it back in the list of open projects, and it
SHALL no longer be listed among the closed ones.

#### Scenario: A closed project leaves the project list and stays reachable

- **Given** a project the user closed
- **When** the user looks at the projects screen
- **Then** the project is not in the list of open projects
- **And** the project is listed among the closed ones

#### Scenario: A closed project with no tasks is still reachable

- **Given** a project with no tasks that the user closed
- **When** the user looks at the projects screen
- **Then** the project is listed among the closed ones

#### Scenario: Opening a closed project puts it back in the list

- **Given** a project the user closed
- **And** the user opens it again
- **When** the user looks at the projects screen
- **Then** the project is in the list of open projects
- **And** the project is no longer listed among the closed ones

#### Scenario: The closed projects are shown when there are no open projects

- **Given** a project the user closed
- **And** no open project
- **When** the user looks at the projects screen
- **Then** the closed project is listed among the closed ones
