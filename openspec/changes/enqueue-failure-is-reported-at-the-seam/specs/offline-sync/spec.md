# offline-sync

## ADDED Requirements

### Requirement: REQ-OS-028 A change that failed to reach the outbox is reported

When queuing a change for sync fails, the failure SHALL be logged and reported
to the crash reporting port. It SHALL NOT be left to a caller that may discard
the returned [Result].

`SyncEngine.enqueue` SHALL perform the reporting itself. The 18 repository call
sites that currently discard its `Result` SHALL NOT be required to handle it.

`enqueue` SHALL continue to return `Result.failure` on the same conditions as
before. Reporting does not replace the return value; a caller that wants to react
still can.

A cancelled enqueue SHALL NOT be reported. Cancellation is not an outage, and
`runCatchingResult` rethrows `CancellationException` before it can become a
`Result`, so a reported failure is always a real error.

**Rationale:** the local row is committed inside `unitOfWork.write`, *before*
`enqueue` runs. A failed enqueue is therefore not a failed write — it is a write
that succeeded locally and will never reach the server, because the patch row
was never inserted and no later cycle has anything to notice. Every other layer
reports the same event: the edit is in the database and the sync screen says "up
to date". Reporting belongs at the seam because `enqueue` is the only place that
knows a patch failed to be queued; 18 call-site log statements would repeat the
sentence across six data-layer classes and still be silent at the next one.

#### Scenario: The outbox refuses the row

- **Given** a write whose entity row has been committed locally
- **And** the outbox insert throws
- **When** `SyncEngine.enqueue` is called
- **Then** it returns `Result.failure`
- **And** the crash reporting port receives the original throwable under the
  `sync.enqueue_failed` issue key

#### Scenario: The reporting is what makes this findable

- **Given** the reporting in `enqueue` has been deleted
- **When** the gate runs against an outbox whose `insert` throws
- **Then** the control fails
- **And** the removal cannot be mistaken for the failure being handled elsewhere

#### Scenario: The enqueue succeeds

- **Given** an outbox that accepts the row
- **When** `SyncEngine.enqueue` is called
- **Then** it returns `Result.success`
- **And** nothing is reported

#### Scenario: The enqueue is cancelled

- **Given** an enqueue whose coroutine is cancelled
- **When** the cancellation reaches `SyncEngine.enqueue`
- **Then** the `CancellationException` propagates
- **And** nothing is reported to the crash reporting port