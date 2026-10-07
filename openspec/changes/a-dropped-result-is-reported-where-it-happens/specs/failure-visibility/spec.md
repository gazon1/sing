# failure-visibility

## ADDED Requirements

### Requirement: REQ-FV-001 A Result that nobody consumes is a finding, not a style note

A production call in `commonMain` that returns `Result` SHALL be reported by
`DroppedResultIsReportedTest` unless the value is consumed, assigned, or reported
where it is produced.

The rule SHALL resolve the declared return type from the **declaring type** of the
receiver's interface, not from the method name. Two declarations of the same name
may disagree, and this tree contains the disagreement: `TaskRepository.upsert`
returns the entity, `ChecklistRepository.upsert` returns `Result<Unit>`, and
`SessionStore.save` returns `Unit` while another `save` returns a `Result`.

A call SHALL NOT be a finding when it is:

- chained — `.getOrThrow()`, `.onSuccess` / `.onFailure`, `.fold`, `.getOrElse`,
  `.map`;
- assigned — `fun x(): Result<T> = repo.y(…)`, `val r = repo.y(…)`;
- inside a lambda opened by a consumer, including `emitError { }` / `catchTo { }`;
- a method that reports its own failure, which is the contract for
  `SyncEngine.enqueue` (REQ-OS-028) and `refreshStatus` (REQ-PROP-001).

A documented worked example in a comment SHALL NOT be a finding.

**Rationale:** `kotlin.Result` carries no `@MustUseReturnValue`, so the compiler
cannot warn and every one of the 32 sites in the sweep of 2026-10-07 was invisible
to the whole build. A rule that reports correct code gets switched off with the
false positives rather than with the real findings, so the three measured
false-positive shapes — a KDoc example, a multi-line chain, a call nested in a
consuming wrapper's lambda — are each pinned by a control.

#### Scenario: A Result is dropped

- **Given** a call to a `Result`-returning repository method written as a bare
  statement
- **When** the gate runs
- **Then** it fails, naming the file, the line and the call
- **And** the message names the three acceptable fixes rather than only the violation

#### Scenario: The resolver stops matching

- **Given** no `Result`-returning declaration is found anywhere in the tree
- **When** the gate runs
- **Then** it fails
- **And** a resolver that has silently stopped matching cannot pass the gate for
  looking like a codebase that has stopped violating

#### Scenario: The same method name on two types

- **Given** `TaskRepository.upsert` returns an entity and `ChecklistRepository.upsert`
  returns a `Result`
- **When** the resolver runs
- **Then** each resolves by its own declaring type
- **And** a control asserts the three-way disagreement, so a merged name map fails

#### Scenario: A call that only looks dropped

- **Given** a call chained across lines, or nested in `emitError { }`, or written out as
  a KDoc example
- **When** the gate runs
- **Then** it is not a finding
- **And** a control asserts each of the three

### Requirement: REQ-FV-002 A restore report counts only what was written

`BackupImporter` SHALL NOT increment `restoredCount` for an attachment whose write
failed. The existing `try`/`catch` around the call SHALL be reached by a failed
write, which requires unwrapping `AttachmentStorage.saveBytes`'s `Result`.

**Rationale:** the `try`/`catch` there is written against a throwing contract and
records the id in `missingIds` on failure — but `saveBytes` returns a `Result`, so
the failure fell through to `restoredCount++` and the catch was never entered. The
restore report therefore counted an attachment that was not on disk, and the count
is what the caller reads.

#### Scenario: An attachment cannot be written

- **Given** a storage that returns `Result.failure`
- **When** the attachment is restored
- **Then** `restoredCount` does not include it
- **And** its id is recorded in the missing list