# Tasks — Observable Behavior

**capability:** `tasks` | **status:** proposed

---

## ADDED Requirements

### Requirement: A task is blocked only by an unfinished prerequisite

A task SHALL be reported as blocked when it has a dependency that blocks it,
and that prerequisite is neither finished nor discarded.

A dependency of any other kind — one that says the task follows up on it, for
instance — SHALL NOT make the task blocked. Every place the app reports a task
as blocked SHALL use that one meaning: the badge, the saved views that select
blocked tasks, the health summary, the filter that hides blocked tasks, and the
list of dependencies offered for editing.

**Rationale:** the dependency graph records the *kind* of every relationship, but
the task remembered only *that* it had dependencies. So "blocked" was computed as
"has a dependency", which made a task that merely follows up on another look
exactly like a task that cannot start. The task's own description had said
"must be completed before" since the beginning; this requirement is that
sentence, enforced.

#### Scenario: An unfinished prerequisite blocks the task

- **Given** a task that depends on another task
- **And** that dependency blocks it
- **And** the dependency is neither finished nor discarded
- **When** the task is reported on
- **Then** it is reported as blocked

#### Scenario: A finished prerequisite does not block the task

- **Given** a task that depends on another task
- **And** that dependency blocks it
- **And** the dependency has been finished
- **When** the task is reported on
- **Then** it is not reported as blocked

#### Scenario: A discarded prerequisite does not block the task

- **Given** a task that depends on another task
- **And** that dependency blocks it
- **And** the dependency has been discarded
- **When** the task is reported on
- **Then** it is not reported as blocked

#### Scenario: A dependency of another kind does not block the task

- **Given** a task that depends on another task
- **And** that dependency says the task follows up on it rather than that it blocks it
- **When** the task is reported on
- **Then** it is not reported as blocked

#### Scenario: Every place that reports blocking agrees

- **Given** a task that follows up on another task
- **When** the app reports on the task in the badge, in a saved view that selects
      blocked tasks, in the health summary, in the filter that hides blocked
      tasks, and in the list of dependencies offered for editing
- **Then** none of them reports the task as blocked

### Requirement: Closing a task with an unfinished prerequisite asks first

Closing a task that has an unfinished prerequisite SHALL NOT take effect on the
first request. The system SHALL ask, naming the prerequisites that are still
open, and SHALL close the task only if the user confirms.

The confirmation SHALL be offered at every place the user can close a task, and
the same set of prerequisites SHALL be named at each of them.

Declining the confirmation SHALL leave the task exactly as it was.

Taking a finished task back into work SHALL NOT be asked about.

**Rationale:** the badge that says "this is not ready" and the checkbox next to
it that closed the task anyway were two halves of one feature with only one
half implemented. The user was told and then overridden by the affordance they
were looking at. Asking costs one interaction and preserves the user's
authority over their own data: sometimes the prerequisite really is done and
the graph is out of date.

#### Scenario: A blocked task asks before it closes

- **Given** a task with a prerequisite that is still open
- **When** the user closes the task
- **Then** the task stays open
- **And** the user is asked, and the open prerequisite is named

#### Scenario: Confirming the request closes the task

- **Given** a task with a prerequisite that is still open
- **And** the user has been asked
- **When** the user confirms
- **Then** the task is closed

#### Scenario: Declining the request leaves the task alone

- **Given** a task with a prerequisite that is still open
- **And** the user has been asked
- **When** the user declines
- **Then** the task is still open
- **And** nothing about the task has changed

#### Scenario: An unblocked task closes without a question

- **Given** a task with no open prerequisite
- **When** the user closes the task
- **Then** the task is closed
- **And** the user is not asked

#### Scenario: Every place that closes a task asks

- **Given** a task with a prerequisite that is still open
- **When** the user closes it from the agenda, from the agenda's context menu,
      from the task's own screen, or from the task's list of subtasks
- **Then** the task stays open
- **And** the user is asked, and the open prerequisite is named

#### Scenario: Taking a finished task back into work is not asked about

- **Given** a task that has been closed
- **When** the user takes it back into work
- **Then** it is open again
- **And** the user was not asked

### Requirement: A write that arrives from outside the user's own action is not asked about

A change to a task's finished state that arrives from synchronisation, from
restoring a backup, or from an accepted proposal SHALL be applied without a
confirmation, even when the task has an open prerequisite.

**Rationale:** the confirmation is a question for the person who is looking at
the screen, not a validation of the data. A device that has been offline for a
week and comes back should not stop and ask about tasks the user did not touch
while they were away, and a restored backup that leaves a task closed against an
open prerequisite is a faithful copy of what was backed up.

#### Scenario: A synchronised change is applied without a question

- **Given** a task with a prerequisite that is still open
- **When** the task's finished state changes through synchronisation
- **Then** the change is applied
- **And** the user is not asked

#### Scenario: A restored task is applied without a question

- **Given** a task with a prerequisite that is still open
- **When** the task is restored from a backup as closed
- **Then** it stays closed
- **And** the user is not asked
