# proposals

## ADDED Requirements

### Requirement: REQ-PROP-001 A status that was not recomputed is reported

When `ProposalRepository.refreshStatus` fails, the failure SHALL be logged and reported
to the crash reporting port under the `proposals.refresh_status_failed` issue key.

The three `ApplyProposalItemUseCase` call sites SHALL NOT be required to handle the
returned `Result`.

`refreshStatus` SHALL continue to return `Result.failure` on the same conditions as
before. It SHALL NOT throw. Reporting does not replace the return value.

**Rationale:** `refreshStatus` is the only thing that recomputes a proposal's aggregate
status from its items, and `watchProposal` serves the stored column — so a failed
recomputation is not a value that self-corrects on the next read. The proposal keeps the
status it had before the items changed, and a stale summary is indistinguishable in the
UI from a proposal that is genuinely still pending.

Reporting rather than throwing is deliberate: the items are already decided correctly
in the database and only the derived summary failed. Throwing would fail a confirm that
genuinely succeeded, over a value the user did not ask for, turning a display defect into
a failed operation. The next confirm or reject retries the reduction.

This requirement does **not** promise that the derived status converges while the
failure persists. What it requires is that the failure becomes visible.

#### Scenario: The status write fails

- **Given** a DAO whose `updateProposalStatus` throws
- **When** `refreshStatus` is called
- **Then** it returns `Result.failure`
- **And** the crash reporting port receives the failure under
  `proposals.refresh_status_failed`
- **And** the recomputation was genuinely attempted, so the report is about the write
  failing rather than about `refreshStatus` never running

#### Scenario: The status write succeeds

- **Given** a DAO that accepts the update
- **When** `refreshStatus` is called
- **Then** it returns `Result.success`
- **And** nothing is reported

#### Scenario: The reporting is removed

- **Given** the reporting in `refreshStatus` has been deleted
- **When** the gate runs against a DAO that throws
- **Then** the control fails
- **And** the removal cannot be mistaken for the failure being handled by a caller

#### Scenario: A caller already handles it

- **Given** a caller that inspects the returned `Result`
- **When** `refreshStatus` fails there
- **Then** the caller still receives `Result.failure`
- **And** the reporting has not replaced the return value it relies on