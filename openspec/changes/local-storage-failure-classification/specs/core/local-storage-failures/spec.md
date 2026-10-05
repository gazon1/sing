# core/local-storage-failures

**capability:** `core/local-storage-failures` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-LSF-001

When reading or writing the device's own state fails, the system **SHALL** report the
failure as a local-storage failure, distinct from a failure of any remote service.

**Rationale:** the two have different remedies. One is fixed by the device, the other by
waiting. A user who cannot tell them apart retries the wrong one, and in the case of local
state that means discarding edits that were never lost.

**Test coverage:** `SyncEngineStorageFailureTest` (the sync cycle today),
`EnumPrefTest` and `DataStoreCatchTest` (the settings layer today).

#### Scenario: A local read fails and a remote service is reachable

- **Given** the device's own state cannot be read
- **And** the remote service is available and would answer
- **When** the operation is attempted
- **Then** the failure is reported as a local-storage failure
- **And** it is not reported as a remote-service failure

#### Scenario: A remote service refuses and local state is readable

- **Given** the remote service refuses the request
- **And** the device's own state is readable
- **When** the operation is attempted
- **Then** the failure is reported as a remote-service failure
- **And** it is not reported as a local-storage failure

---

### Requirement: REQ-LSF-002

A reported local-storage failure **SHALL** identify the operation that failed, in terms
the user can act on, rather than repeating the text of the underlying storage driver.

**Rationale:** the driver's text describes an implementation detail and generally a line
number. "Could not read the pending changes" states what failed; "attempt to re-open an
already-closed object" states what the driver did, which the user cannot act on and the
developer does not need, because the driver's own text is preserved for that.

**Test coverage:** `SyncEngineStorageFailureTest`.

#### Scenario: The reported wording

- **Given** a local read fails
- **When** the failure is reported to the user
- **Then** the report names the operation that failed
- **And** the underlying driver text is not the text shown to the user

---

### Requirement: REQ-LSF-003

After any failure, the system **SHALL NOT** report a synchronization operation as still in
progress.

**Rationale:** the reported state is what callers check before starting the next cycle.
A state that reads as "running" after the work has ended is a refusal, not a
synchronization: one storage failure at the wrong moment otherwise leaves every later
attempt declining itself, until the process restarts.

**Test coverage:** `SyncEngineStorageFailureTest` (the stranded-state case, and the
recovery case that follows it).

#### Scenario: A failure while a cycle is in progress

- **Given** a synchronization cycle has begun
- **And** a local read fails partway through
- **When** the failure is reported
- **Then** the system does not report that cycle as in progress
- **And** work that was still queued remains queued

#### Scenario: The next attempt after a failure

- **Given** a previous cycle failed to read local state
- **When** local state becomes readable again
- **Then** a later cycle is permitted to run
- **And** it is not declined on the grounds that a cycle is still running

---

### Requirement: REQ-LSF-004

A synchronization cycle that failed before either direction was attempted **SHALL NOT** be
reported as two failed operations.

**Rationale:** an outcome carrying one result per direction has nowhere honest to put a
failure that happened before either ran. Filling both asserts work that may never have
started, and a background worker that decides whether to retry from those results is then
reasoning about work that does not exist.

**Test coverage:** `SyncEngineStorageFailureTest` (the cycle-outcome case),
`SyncCoordinatorCoalescingTest` (the coordinator's own fallback).

#### Scenario: A cycle fails before pushing or pulling

- **Given** a local read the cycle depends on fails
- **When** the cycle reports its outcome
- **Then** the outcome states that the cycle did not complete
- **And** it does not state that a push and a pull both failed

---

### Requirement: REQ-LSF-005

A reported failure **SHALL** retain the underlying failure, so that the report identifies
where the failure came from and not only that one occurred.

**Rationale:** every failure path constructs its report inside a catch block, so a report
without the cause carries a stack that ends at the catch and the frames that would have
said what failed are gone. The classification is what a user acts on; the cause is what a
developer needs, and the one place to lose it is the moment it is wrapped.

**Test coverage:** `SyncEngineStorageFailureTest` (the preservation case),
`SyncCoordinatorCoalescingTest` (preservation through the coordinator's fallback).

#### Scenario: A failure is wrapped for reporting

- **Given** an operation fails with an underlying error
- **When** the failure is reported
- **Then** the report retains the underlying error
- **And** the original failure's own description is still available to a developer

---

### Requirement: REQ-LSF-006

A failure that is not a local-storage failure and not a remote-service failure **SHALL**
still be reported, and **SHALL NOT** be silently discarded in order to satisfy the
requirements above.

**Rationale:** classification is the mechanism, not the goal. A cancellation must
propagate rather than be reported as a failed read, and a programming error must reach a
developer rather than be rendered to a user as "storage unavailable". A rule that
classified everything would satisfy REQ-LSF-001 and be wrong about everything else.

**Test coverage:** `DataStoreCatchTest` (the non-storage propagation case),
`SyncEngineStorageFailureTest`.

#### Scenario: An operation fails for a reason that is not storage

- **Given** an operation fails and the failure is not a local-storage failure
- **When** it is reported
- **Then** it is reported as unclassified
- **And** it is not reported as a local-storage failure
