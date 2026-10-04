# crash-reporting

## ADDED Requirements

### Requirement: REQ-1 A handled failure is reported when it is handled

The system SHALL report a handled failure to the crash-reporting service at the moment the
application handles it, so that a failure which is shown to the user is also recorded for the
maintainer. Exactly one report SHALL be produced per handled failure.

Reporting SHALL occur before the failure is presented, so that anything the error path records
afterwards cannot be ordered ahead of the event it relates to.

A failure that the application handles successfully SHALL NOT be reported.

#### Scenario: A record the user tried to delete no longer exists

- **Given** the user deletes a tag that has already been removed
- **When** the application handles the resulting failure
- **Then** the user sees a message
- **And** exactly one report is sent, grouped under a fixed key for that kind of missing record

#### Scenario: An operation completes successfully

- **Given** the user saves a note and the write succeeds
- **When** the operation returns
- **Then** no report is sent

#### Scenario: Work is cancelled

- **Given** the user leaves the screen while a save is in flight
- **When** the work is cancelled
- **Then** no report is sent, because cancellation is not a defect

### Requirement: REQ-2 Reports are grouped by a stable key, never by prose

Every report SHALL carry a grouping key. The key SHALL be either the error's own stable domain
code or a fixed label declared at the reporting site. The key SHALL NOT be derived from an
error message, a record's contents, or any other user input, because the key is transmitted
off the device and is the identity of an issue for as long as the record exists.

Two occurrences of the same defect SHALL therefore produce the same key even when their
messages differ, and SHALL appear as one issue.

#### Scenario: Two different messages from the same defect

- **Given** a missing record is reported twice, with different identifiers in each message
- **When** both reports are received
- **Then** they share one grouping key and appear as one issue

#### Scenario: A raw exception with no domain code

- **Given** a failure surfaces as a plain runtime exception with no domain code
- **When** it is reported
- **Then** the grouping key is the fixed label declared at that reporting site

### Requirement: REQ-3 No report carries user content or credentials

A report SHALL NOT contain a task title, a note body, a tag name, a search query, or any other
text the user entered. A report SHALL NOT contain an access token, an application key, a
network address containing embedded credentials, or an email address.

This applies to every field of the report, including its description, its grouping key, and any
log lines attached to it.

#### Scenario: An exception message contains an email address

- **Given** a failure whose message contains an email address
- **When** the failure is written to the on-device log
- **Then** the address does not appear in the recorded text

#### Scenario: A credential is nested in a cause

- **Given** a failure whose cause chain contains a credential
- **When** the failure is written to the on-device log
- **Then** the credential does not appear anywhere in the recorded text

### Requirement: REQ-4 The on-device log records where the failure actually happened

A failure written to the on-device log SHALL record the location that raised it. The recorded
frames SHALL NOT be those of the redaction or logging code that handled the failure.

A user who shares a log bundle with a bug report SHALL therefore receive frames that identify
the failing operation.

#### Scenario: A failure raised by a repository write

- **Given** a write fails deep inside the data layer
- **When** the failure is written to the log file
- **Then** the recorded frames name the data layer, not the logging code

### Requirement: REQ-5 Reporting never changes application behaviour

A failure in the reporting service SHALL NOT propagate to the application, and SHALL NOT change
whether a handled error is presented. Reporting SHALL NOT block the operation that triggered it.

The system SHALL continue to function with reporting switched off, and a build SHALL succeed
whether or not reporting credentials are configured.

#### Scenario: The reporting service is unreachable

- **Given** a failure is being reported and the reporting service cannot be contacted
- **When** the application handles the original failure
- **Then** the user still sees the same message
- **And** the application does not crash

#### Scenario: No credentials are configured

- **Given** a build with no reporting credentials
- **When** the application is built and launched
- **Then** the build succeeds and the application runs normally with reporting inert

### Requirement: REQ-6 Every error-handling surface is connected to reporting

An application component that handles failures SHALL be connected to the reporting service.
A component SHALL NOT silently fall back to reporting nothing.

This SHALL be enforced by a build-time check, so that disconnecting a component fails the build
rather than degrading silently.

#### Scenario: A component stops passing a reporter

- **Given** a component that handles failures no longer declares a reporting dependency
- **When** the check runs
- **Then** the check fails and names the component

### Requirement: REQ-7 A background failure does not end the session or the process

A long-running background collector SHALL survive a failure in one of its iterations. Such a
failure SHALL NOT terminate the process, and SHALL NOT permanently stop the work the collector
performs.

Starting such a collector more than once SHALL have no additional effect.

#### Scenario: A storage error during a background pass

- **Given** a background collector is running
- **When** one iteration fails because storage is unavailable
- **Then** the application keeps running
- **And** the collector remains able to serve the next request

#### Scenario: The collector is started twice

- **Given** a collector has already been started
- **When** it is started again
- **Then** nothing changes and no failure occurs

### Requirement: REQ-8 The application module is checked by static analysis

The application module SHALL be analysed by the project's static analysis tooling as part of
the standard lint command, and a violation in it SHALL fail the build in the same way as a
violation in any other module.

The source directories declared for analysis SHALL all exist.

#### Scenario: A new violation is introduced in the application module

- **Given** a change introduces a violation in the application module
- **When** the standard lint command runs
- **Then** the command fails and reports the violation

#### Scenario: The lint command is inspected

- **Given** the standard lint command is read
- **Then** it includes the application module
