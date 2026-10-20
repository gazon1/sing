# version-gate

## ADDED Requirements

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
