# change-review-integrity Specification

## Purpose
A review verdict that names what it reviewed — the commit and the digest
of the change's contract documents — and a claim about CI that names the
workflow, the run and the conclusion.

## Requirements

### Requirement: REQ-1 A review verdict names what it reviewed

A review verdict SHALL state the commit it was made against and the digest of
the change's contract documents, and the gate that checks the verdicts SHALL
recompute that digest and compare it.

The contract of a change is its proposal and its spec deltas — what the change
requires of the code. The task list is not part of it: a checklist moves while
the work is done, and a verdict that has to be rewritten every time the work
moves is a verdict nobody keeps. A document at the root of the change, such as
a `design.md`, is outside the digest for the same reason: the spec delta is
where the contract is written.

A contract that moves SHALL leave a verdict that the gate reports, naming both
digests: the one the verdict carries and the one the tree carries. The change
that moves the contract SHALL re-issue the verdict with the digest it now
carries, in the same diff, so that a reviewer sees the move and the re-issue
together.

The digest SHALL be computed over the documents themselves, and the gate SHALL
compute it the same way the verdict records it — one implementation, so the
recorded value and the checked value cannot disagree.

**Rationale:** a verdict file proves that a review happened. It does not prove
that the review was of the change in the tree, and the difference is invisible
until a later session acts on a "pass" that was given to different text.

#### Scenario: The contract changed after the review

- **Given** a change whose verdict records the digest of its contract
- **When** the contract is edited
- **Then** the gate fails
- **And** it names the digest the verdict carries and the digest the tree carries
- **And** it says what clears the gate: re-review, and re-issue the verdict with the digest the change now carries

#### Scenario: The checklist moved after the review

- **Given** a change whose verdict records the digest of its contract
- **When** a task in the task list is ticked
- **Then** the gate is quiet
- **And** the digest is unchanged

#### Scenario: The verdict names no commit

- **Given** a change whose verdict carries no `reviewed:` field
- **When** the gate runs
- **Then** it fails
- **And** it says which field is missing

### Requirement: REQ-2 A claim about CI names the workflow, the run and the conclusion

A report that a change is green SHALL name the workflow that decided, the run,
and the conclusion that run reached. A run id on its own SHALL NOT be taken as
such a claim, because a repository runs several workflows and the one that
answered "success" may not be the build.

The claim SHALL be available from one tool, and that tool's exit code SHALL be
the conclusion: success is zero, any other conclusion is one, and the absence of
a run is two.

A missing run SHALL NOT be reported as a pass, and a run list that could not
be read SHALL NOT be reported as a run.

**Rationale:** a session read the first successful line of a run list and
reported a green CI for a red branch. The list held several workflows, and the
one that had succeeded was the cache cleanup. Nothing in the list said which
workflow it was.

#### Scenario: The run list holds several workflows

- **Given** a run list that holds a successful cache cleanup and a failed build
- **When** the verdict is read
- **Then** the build answers
- **And** the cache cleanup is not the claim

#### Scenario: There is no run of that workflow

- **Given** a run list that holds no run of the workflow that decides
- **When** the verdict is read
- **Then** it reports that there is no run
- **And** the exit code is two, not zero

#### Scenario: A run names no workflow

- **Given** a run list in which a run names no workflow
- **When** the verdict is read
- **Then** the run is attributed to no workflow
- **And** the tool reports that there is no run of the workflow that decides
- **And** the exit code is two, not zero

#### Scenario: The run list could not be read

- **Given** a run list that could not be read at all
- **When** the verdict is read
- **Then** the exit code is three, and the line says the list could not be read
- **And** it is not a pass

#### Scenario: The run has not concluded

- **Given** a run that is still in progress
- **When** the verdict is read
- **Then** the exit code is not zero
- **And** the line says that the run has not concluded
