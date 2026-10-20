# version-gate

## ADDED Requirements

### Requirement: The version gate never holds the app in front of a read that does not answer

The version gate SHALL bound every remote-config read it makes by a deadline, and a read
that misses the deadline SHALL be treated exactly as a read that failed.

A read that fails and a read that never returns are the same failure with a different
fuse, and only one of them throws. A hang produces no report, no breadcrumb and no
state change: the splash stays, and nothing anywhere says why. The gate is a full-screen
splash in front of the whole app, so a hang takes the entire app down with it, not just
the gate.

The deadline SHALL be configurable, so that a test can state it and a platform can
override it, and the default SHALL be long enough for a first-launch database creation
and short enough that a splash does not look like a hang.

#### Scenario: The read answers in time

- **Given** the remote config answers within the deadline
- **When** the version gate checks
- **Then** the snapshot it read is evaluated
- **And** the app is admitted or blocked on that snapshot, as before

#### Scenario: The read never answers

- **Given** the remote config read does not answer within the deadline
- **When** the version gate checks
- **Then** the read is abandoned
- **And** the default snapshot is evaluated
- **And** the failure is reported
- **And** a breadcrumb records that the gate was bypassed and why

#### Scenario: The check is repeated

- **Given** the user asks the gate to check again
- **When** the refresh does not answer within the deadline
- **Then** the refresh is abandoned on the same deadline as the first check
- **And** the gate is admitted on the defaults

### Requirement: A bypass is visible and names its reason

A gate that admits a user it should have blocked is a decision that leaves no trace in the
end state: the outcome is byte-for-byte what a healthy read produces, and a config outage
would read as a healthy dashboard for as long as it lasted.

The bypass SHALL therefore be recorded where the failure report can be read: as a report
entry, with its own machine-shaped code, and as a breadcrumb that is written before the
report so that it rides with it.

The breadcrumb SHALL name the reason. A read that *threw* and a read that *timed out* are
two different investigations — one points at the config server, the other at the storage
underneath it — and a wording that covers both points at neither.

#### Scenario: The read threw

- **Given** the remote config read threw
- **When** the version gate checks
- **Then** the failure is reported
- **And** the breadcrumb says the remote config was unreadable
- **And** the breadcrumb is written before the report

#### Scenario: The read timed out

- **Given** the remote config read did not answer within the deadline
- **When** the version gate checks
- **Then** the failure is reported under a code that names the timeout
- **And** the breadcrumb says the remote config read timed out
- **And** the breadcrumb does not blame the config server
