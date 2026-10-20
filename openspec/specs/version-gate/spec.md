# version-gate Specification

## Purpose

The version gate is a full-screen splash in front of the whole app. It asks one
question — is this build older than the minimum the backend supports? — and it
answers it from a remote config that the device may not be able to read.

That makes the gate the worst possible place for an unbounded read. The gate is
not a screen behind the app; it is *in front* of it. A read that fails is a
bounded problem, because the gate can admit the user on the defaults and say so.
A read that never returns is the same failure with a longer fuse, and a fuse
that never burns is a hang: the splash stays, the app never starts, and nothing
anywhere says why.

This capability covers the three decisions that were open after the gate
shipped:

- **What happens when the config cannot be read at all.** The gate fails *open*:
  it reports the failure, admits the user on the defaults, and records that it
  did. A broken config server must not be able to brick every install behind a
  gate nobody can pass.
- **What happens when the read does not come back.** The same, plus a deadline.
- **Where the deadline lives.** In the port, not only in the caller — because a
  deadline in a caller can only end a *cooperative* read, and a read that blocks
  inside a driver call ignores cancellation completely.

The requirements are about the gate's *observable* behaviour: what the user sees,
what is reported, and what is recorded. They do not constrain how the config is
fetched, cached or stored.

## Requirements

### Requirement: REQ-1 The version gate never holds the app in front of a read that does not answer

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

### Requirement: REQ-2 A bypass is visible and names its reason

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

### Requirement: REQ-3 The read boundary belongs to the port, not only to the caller

A deadline in a caller can only end a **cooperative** read — one that honours
cancellation. A read that blocks inside a driver call, a lock, or a socket read
ignores cancellation entirely, and the caller's deadline expires without the
read ending. The caller is then past its deadline with a read still in flight:
exactly the hang the deadline was added to prevent.

The remote-config port SHALL therefore bound the reads it performs itself, and
a read that misses the port's bound SHALL end on the port's terms: the
last-known-good snapshot for a cached read, and a failure carrying a
machine-shaped timeout code for a fetch.

A caller MAY still impose a deadline of its own on top of the port's — the
version gate does, because the deadline the user experiences is a promise the
gate makes about itself. The two bounds SHALL be the same number, so that the
gate's promise and the port's guarantee cannot drift apart.

#### Scenario: A read ignores cancellation

- **Given** a remote-config read that does not return when it is cancelled
- **When** the port is asked for a snapshot
- **Then** the port gives up on the read and answers with the last-known-good
  snapshot
- **And** the caller is not held past the port's bound

#### Scenario: A fetch ignores cancellation

- **Given** a remote-config fetch that does not return when it is cancelled
- **When** the port is asked to refresh
- **Then** the refresh fails with a timeout code of its own
- **And** the failure is one a caller that already handles failures handles

#### Scenario: The gate and the port disagree about the bound

- **Given** the gate's deadline and the port's bound
- **When** both are read
- **Then** they are the same number

## What the requirements cost, and what they bought

REQ-1 and REQ-2 were satisfied by giving the gate's read a deadline and by
routing a missed deadline through the same funnel as a thrown read: one
decision point, one ordering guarantee, one fail-open outcome. The timeout
carries its own code, so a slow disk and a broken config server stay separate
rows in the report.

REQ-3 is the requirement that the first two could not enforce on their own. The
gate's deadline is a promise about the *user's* experience; the port's bound is
a guarantee about the *implementation*. A promise is not a guarantee, and the
gap between them is where a driver that ignores cancellation lives.
