# Project Closing — Observable Behavior

**capability:** `projects/project-closing` | **status:** proposed

---

## ADDED Requirements

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
