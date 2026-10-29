# core/refresh-gesture — Observable Behavior

**capability:** `core/refresh-gesture` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-RG-001 The gesture is offered where the user's data is shown, and is not offered where the user is writing

The system SHALL offer a refresh gesture on every screen that shows the user's
stored data: the bottom-bar tabs, the menu destinations, and the detail
screens reached from them. The system SHALL NOT offer it on a screen where the
user is composing content, where the user signs in, or where the user
configures the application.

The rule that separates the two is whether the screen shows stored data or
asks for input. A screen that asks for input has something of the user's that
is not saved yet, and a refresh is not a moment to change it.

#### Scenario: A screen that shows the user's tasks

- **Given** the user is on a screen that lists their tasks
- **When** the user pulls the content down
- **Then** the refresh starts

#### Scenario: A screen where the user is writing

- **Given** the user is editing a note
- **Then** no refresh gesture is offered
- **And** a refresh started elsewhere does not change what they are writing

#### Scenario: A screen where the user signs in

- **Given** the user is on a sign-in screen
- **Then** no refresh gesture is offered

### Requirement: REQ-RG-002 A refresh moves completed work into the archive, and then synchronises

A refresh SHALL move every completed task of the active profile into the
archive, and SHALL then run a synchronisation cycle. The archive step SHALL run
before the synchronisation step.

The archive step SHALL cover the whole active profile and not only the tasks
that happen to be visible on the screen the gesture was made on. The
synchronisation step SHALL be the same cycle the application runs on its own
schedule, and SHALL NOT run as a second cycle beside it.

#### Scenario: Completed work leaves the working lists

- **Given** the user has completed three tasks that are not in the archive
- **When** the user makes a refresh
- **Then** the three tasks are in the archive
- **And** they are no longer in the lists that showed them

#### Scenario: Work that is not completed stays where it is

- **Given** the user has a task that is not completed
- **When** the user makes a refresh
- **Then** the task is not moved to the archive

#### Scenario: The archive step runs before the synchronisation step

- **Given** the user makes a refresh
- **When** the refresh runs
- **Then** the completed tasks are moved to the archive before the
      synchronisation cycle starts

#### Scenario: The refresh covers the whole profile, not the screen

- **Given** the user has completed tasks that are not on the screen the gesture
      was made on
- **When** the user makes a refresh
- **Then** those tasks are moved to the archive as well

#### Scenario: Another profile is not touched

- **Given** the user has two profiles and the active one is the first
- **When** the user makes a refresh
- **Then** only the first profile's completed tasks are moved to the archive

### Requirement: REQ-RG-003 The archived state leaves the device within the same refresh

The state of the archive after a refresh SHALL reach the server as part of that
refresh, so that another device sees it without waiting for the next scheduled
synchronisation.

The archive step writes the archived tasks to the outgoing queue before the
synchronisation cycle reads it. A refresh that synchronised first would leave
the server holding the un-archived copy, and the next download would restore
every task the user had just cleared away.

#### Scenario: The other device sees the archive without waiting

- **Given** the user completes a task on one device
- **When** the user makes a refresh on that device
- **And** the user makes a refresh on the other device
- **Then** the task is in the archive on the other device
- **And** it does not come back into the working lists

#### Scenario: The archived task is not restored by the next download

- **Given** the user completed a task and refreshed
- **When** the device downloads changes again
- **Then** the task is still in the archive

### Requirement: REQ-RG-004 A refresh that cannot reach the server still archives, and says so

A refresh SHALL move the completed work into the archive whether or not the
synchronisation can reach the server. A refresh that cannot synchronise SHALL
report that it did not, SHALL say why in words the user can act on, and SHALL
say that the archive step already happened on the device.

A refresh on a device with no account SHALL move the completed work and SHALL
report that there was nothing to synchronise with. That is not a failure, and
it is not reported as one.

The two steps SHALL fail independently: a failure of the archive step SHALL NOT
prevent the synchronisation, and a failure of the synchronisation SHALL NOT
undo the archive step.

#### Scenario: No network

- **Given** the server cannot be reached
- **When** the user makes a refresh
- **Then** the completed tasks are in the archive on the device
- **And** the report says the synchronisation did not happen and why

#### Scenario: No account

- **Given** the user is not signed in
- **When** the user makes a refresh
- **Then** the completed tasks are in the archive
- **And** the report says there was nothing to synchronise with

#### Scenario: The local changes are not lost

- **Given** the server cannot be reached
- **When** the user makes a refresh
- **Then** the changes the user made before it are still there
- **And** they reach the server at the next successful synchronisation

#### Scenario: The archive step failing does not stop the synchronisation

- **Given** the archive step fails
- **When** the refresh runs
- **Then** the synchronisation still runs
- **And** the report says the archive step failed and why

### Requirement: REQ-RG-005 The report belongs to the screen that asked for it

A refresh SHALL report its outcome on the screen the gesture was made on. No
other screen SHALL report the same refresh.

The report SHALL name how many tasks the refresh moved into the archive and
whether the synchronisation succeeded. A refresh that moved nothing SHALL say
so, and SHALL NOT be reported as a failure.

#### Scenario: The screen that asked reports the outcome

- **Given** the user makes a refresh on one screen
- **When** the refresh finishes
- **Then** that screen reports the outcome
- **And** no other screen reports it

#### Scenario: Nothing to move is not a failure

- **Given** the user has no completed tasks outside the archive
- **When** the user makes a refresh
- **Then** the report says there was nothing to move
- **And** the report is not an error

### Requirement: REQ-RG-006 A second gesture joins the refresh that is running

While a refresh is running, a further refresh request SHALL NOT start a second
run. It SHALL join the running one, and both the screen that asked first and
the screen that asked second SHALL receive the outcome of that one run. No
change SHALL be sent to the server twice because of it.

#### Scenario: Two gestures in a row

- **Given** the user makes a refresh
- **And** the user makes another refresh while the first is still running
- **Then** one run happens
- **And** both screens receive the outcome of that run

#### Scenario: A refresh that is already running

- **Given** a refresh is running
- **When** the user pulls the content down again
- **Then** the screen shows that the refresh is in progress
- **And** no second run starts

#### Scenario: The screen stays usable while the refresh runs

- **Given** a refresh is running
- **When** the user scrolls the content
- **Then** the content scrolls

### Requirement: REQ-RG-007 A refresh finishes even if the user leaves the screen

A refresh SHALL run to its end even if the user navigates away from the screen
the gesture was made on. The report SHALL be waiting when the user returns to
that screen.

#### Scenario: The user leaves while the refresh runs

- **Given** the user makes a refresh
- **When** the user navigates to another screen and the refresh is still running
- **Then** the refresh finishes
- **And** the report is shown when the user returns to the screen

#### Scenario: The refresh is not left half-done

- **Given** the user leaves the screen while the refresh runs
- **When** the refresh ends
- **Then** the completed work is in the archive
- **And** the synchronisation has run
