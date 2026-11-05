# tasks

## ADDED Requirements

### Requirement: Every agenda list of tasks offers a way to create one

Every screen that lists the user's tasks SHALL offer the shell's add-task action.

This holds for the agenda tabs, for the views that list tasks by their health, for
the drill-downs into one project or one or more tags, and for the results of a saved
view. The screens that manage saved views — the list of saved views, the editor of
a saved view and the screen that creates one — SHALL NOT offer it, because they do
not list tasks.

The task the action creates SHALL carry no due date, and SHALL NOT be bound to the
project or the tags being viewed.

**Rationale:** A list of tasks is a list of tasks, and the action's promise is that
the work can be started from where it is read. A saved view's results are a filter
applied to the same list, so the person standing on it is reading tasks. The screens
that manage saved views are not reading tasks, and an action there would offer to
create something the screen is not about.

#### Scenario: A project list offers the add-task action

- **Given** the user opens the tasks of one project
- **When** the user looks at the screen
- **Then** the add-task action is offered
- **And** tapping it opens the task editor with no due date set

#### Scenario: A tag list offers the add-task action

- **Given** the user opens the tasks carrying one tag
- **When** the user looks at the screen
- **Then** the add-task action is offered
- **And** tapping it opens the task editor with no due date set

#### Scenario: A set of tags offers the add-task action

- **Given** the user opens the tasks carrying a chosen set of tags
- **When** the user looks at the screen
- **Then** the add-task action is offered

#### Scenario: A saved view's results offer the add-task action

- **Given** the user opens the results of a saved view
- **When** the user looks at the screen
- **Then** the add-task action is offered

#### Scenario: The saved views' own screens offer nothing

- **Given** the user opens the list of saved views, the editor of a saved view or the
  screen that creates one
- **When** the user looks at the screen
- **Then** the add-task action is not offered

#### Scenario: A new agenda list of tasks is added

- **Given** a new agenda screen that lists tasks
- **When** the test suite is compiled
- **Then** it fails to compile until the screen is classified, so that no list of
  tasks can ship without the question of whether it offers the action being answered
