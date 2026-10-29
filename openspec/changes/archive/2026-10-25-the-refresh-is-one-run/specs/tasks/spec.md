# tasks — Observable Behavior

**capability:** `tasks` | **status:** proposed

---

## ADDED Requirements

### Requirement: Completed work can be moved into the archive in bulk, and stays reversible

The system SHALL be able to move every completed task of the active profile
into the archive in one operation. The operation SHALL move only tasks that are
completed and not already in the archive, SHALL leave the tasks that are not
completed where they are, and SHALL NOT move a task that is already in the
archive a second time.

A task moved into the archive this way SHALL be the same kind of archived task
as one archived from a single task's menu: it SHALL be restorable, and it
SHALL NOT become permanently undeletable.

The bulk move is a consequence of a user action elsewhere in the application —
a request to refresh the screen — and the fact that the move happened SHALL be
reported to the user together with what it did.

#### Scenario: Every completed task of the profile moves at once

- **Given** the user has completed four tasks that are not in the archive
- **When** the completed work is moved into the archive in bulk
- **Then** all four tasks are in the archive

#### Scenario: Work that is not completed does not move

- **Given** the user has a task that is not completed
- **When** the completed work is moved into the archive in bulk
- **Then** the task is not moved

#### Scenario: A task already in the archive is not moved again

- **Given** the user has a task that is already in the archive
- **When** the completed work is moved into the archive in bulk
- **Then** the task stays in the archive
- **And** it is not counted among the tasks that were moved

#### Scenario: Another profile's tasks do not move

- **Given** the user has two profiles and the active one is the first
- **When** the completed work is moved into the archive in bulk
- **Then** only the first profile's completed tasks are moved

#### Scenario: A task moved in bulk can be restored

- **Given** the user completed a task and it was moved into the archive in bulk
- **When** the user restores it from the archive
- **Then** the task returns to the list it came from

#### Scenario: The user is told what moved

- **Given** the user asked for a refresh and four tasks were moved into the
      archive
- **When** the refresh reports its outcome
- **Then** the report says that four tasks were moved
