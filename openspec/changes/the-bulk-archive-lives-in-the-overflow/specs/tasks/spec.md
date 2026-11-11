# tasks

**capability:** `tasks` | **status:** proposed

## ADDED Requirements

### Requirement: The bulk archive is a confirmed overflow action

The Archive screen SHALL offer a single bulk action that moves every completed
live task of the active profile to the archive. The action SHALL be reachable
from the screen's top-bar overflow, SHALL be unavailable when no task is
eligible, and SHALL be confirmed before it writes.

The confirmation SHALL name the number of eligible tasks. Dismissing it SHALL
perform no write. A confirmed sweep SHALL report the number of tasks the
repository actually archived, or say that nothing was archived, and a failed
sweep SHALL be reported to the user.

#### Scenario: The action is in the overflow, not in the content

- **Given** the Archive screen
- **Then** no full-width bulk-archive control is present in the content area
- **And** the bulk action is reachable from the top-bar overflow

#### Scenario: The confirmation names the count

- **Given** three completed tasks that are not archived
- **When** the user opens the bulk action
- **Then** the confirmation names three eligible tasks
- **And** the confirm action names the same number

#### Scenario: Cancel writes nothing

- **Given** a completed task that is not archived
- **When** the user dismisses the confirmation
- **Then** the task remains a live task
- **And** the archive list is unchanged

#### Scenario: Confirm performs one sweep

- **Given** a completed task that is not archived
- **When** the user confirms
- **Then** the task is archived
- **And** the user is told the number of tasks that were archived

#### Scenario: A sweep that archived nothing says so

- **Given** the archive write finds no eligible task, because another device
  moved the same work between the dialog and the confirmation
- **When** the user confirms
- **Then** the user is told that nothing was archived

#### Scenario: Zero eligible tasks cannot start a write

- **Given** no completed task that is not archived
- **Then** the bulk action is unavailable
- **And** opening it cannot start a sweep

#### Scenario: The archive list and zero eligibility are different states

- **Given** the archive holds tasks and no live task is eligible
- **Then** the archive list is shown
- **And** the bulk action is unavailable rather than the screen being empty

#### Scenario: A failed sweep is reported

- **Given** the archive write fails
- **When** the user confirms
- **Then** the failure is reported to the user
- **And** no task is reported as archived
