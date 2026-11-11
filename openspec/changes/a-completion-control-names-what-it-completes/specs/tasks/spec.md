# tasks

## ADDED Requirements

### Requirement: A completion control names the thing it completes

Every control that completes a piece of work SHALL expose, as its accessible
name, the kind of the thing and the thing's own title — a task, a subtask or a
checklist item.

The name SHALL be localized. The name SHALL NOT be empty, and it SHALL NOT
depend on a test tag, on a sibling label, or on visible text elsewhere in the
row.

The completion state SHALL be carried by the control as a state, separate from
its name. The name SHALL be the same whether the thing is done or not, so that
the state is what changes and is announced as a change.

The three controls that complete a task, a subtask and a checklist item SHALL
take their name from one shared source, so that a wording change is made once.

**Rationale:** The task family's completion controls reached the platform
unnamed. The control on a task row had a role and a checked state but no name;
the control on a task card carried a generic description that named no task and
was hardcoded in English, so it was not localized at all; the shared row that
renders both subtasks and checklist items had a role and a state but no name
either. On a list of twenty tasks every checkbox therefore announced the same
thing, and a screen reader user could not tell one from another without walking
the surrounding text — the work the accessible name exists to do.

The name names the thing rather than the action. The control already carries the
checkbox role and the completion state, which is what says "toggle this
thing's completion"; repeating the action inside the name goes stale as soon as
the control is a toggle rather than a one-way action. Keeping the state out of
the name also lets one name serve both states, so the user hears the same thing
on every visit and hears the state change separately.

#### Scenario: A user reads a list of tasks with a screen reader

- **Given** a list of tasks, each with a completion control
- **When** the user moves to a task's completion control
- **Then** the control is announced as a checkbox
- **And** it is announced with the kind of the thing and the thing's title
- **And** the announced state says whether the thing is done

#### Scenario: A user reads a task card with a screen reader

- **Given** a task shown as a card with a completion control
- **When** the user moves to that control
- **Then** the control is announced as a checkbox
- **And** it is announced with the kind of the thing and the thing's title
- **And** the announced state says whether the task is done

#### Scenario: A user reads a subtask with a screen reader

- **Given** a subtask shown in a list under its parent task
- **When** the user moves to the subtask's completion control
- **Then** the control is announced with the subtask kind and the subtask's title
- **And** the announced state says whether the subtask is done

#### Scenario: A user reads a checklist item with a screen reader

- **Given** a checklist item shown in a checklist
- **When** the user moves to the item's completion control
- **Then** the control is announced with the checklist-item kind and the item's title
- **And** the announced state says whether the item is done

#### Scenario: The thing being completed has no title

- **Given** a task, subtask or checklist item whose title is empty
- **When** the user moves to its completion control
- **Then** the control still has a non-empty name
- **And** the name identifies the kind of the thing without a dangling separator

#### Scenario: The control is activated

- **Given** a completion control with a name and a state
- **When** the user activates it
- **Then** the thing's completion state changes
- **And** the control's name stays the same

#### Scenario: The wording changes

- **Given** three controls that complete a task, a subtask and a checklist item
- **When** the wording of the names is changed
- **Then** the change is made once, in the shared source
- **And** all three controls announce the new wording
