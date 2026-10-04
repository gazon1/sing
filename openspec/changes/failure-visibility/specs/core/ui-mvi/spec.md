# core/ui-mvi

## ADDED Requirements

### Requirement: REQ-1 A notification sent after its audience is gone is discarded silently

A one-shot notification addressed to a screen SHALL, once that screen has been dismissed, be
discarded without raising an error and without interrupting the work that sent it.

That race is a lifecycle boundary rather than a defect. It SHALL NOT be recorded by the
crash-reporting service, because no report can be acted on and its volume grows with how much
work happens to be unfinished at dismissal.

#### Scenario: Work is still running when its screen is dismissed

- **Given** background work started by a screen that is still in progress
- **When** the screen is dismissed and the work then tries to notify it
- **Then** the notification is discarded, the work completes normally, and nothing is recorded

#### Scenario: The notification is sent while the screen is present

- **Given** a screen that has not been dismissed
- **When** a one-shot notification is sent to it
- **Then** it is delivered exactly once

### Requirement: REQ-2 A user action that fails leaves an outcome the user can observe

An action the user took SHALL, when it fails, produce an outcome the user can perceive. Silently
discarding the result is not an outcome, and neither is a state that looks unchanged.

The failure SHALL ALSO be recorded by the crash-reporting service. Observability for the user and
diagnosability for the maintainer are separate requirements, and satisfying one does not satisfy
the other.

Where a screen cannot currently express a failure, the capability to do so SHALL be added. A
screen's inability to show a failure is not grounds for waiving the requirement.

#### Scenario: A user marks an item complete and the change is not saved

- **Given** a user marks an item complete in a list
- **When** the change is not persisted
- **Then** the user can tell that it failed, and the failure is recorded under a stable grouping
  key

#### Scenario: The change is saved

- **Given** a user marks an item complete in a list
- **When** the change is persisted
- **Then** the item's state changes and nothing is recorded

#### Scenario: A user deletes an item and the change is not saved

- **Given** a user requests the deletion of an item
- **When** the change is not persisted
- **Then** the user can tell that it failed, and the failure is recorded under a stable grouping
  key
