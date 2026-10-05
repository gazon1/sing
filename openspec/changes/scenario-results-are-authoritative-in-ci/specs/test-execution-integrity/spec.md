# test-execution-integrity

## ADDED Requirements

### Requirement: REQ-12 A scenario's result is authoritative, and its absence is stated

A build that reports a result for a scenario on a target that the scenario claims
SHALL fail if that target produced no result for the build's own commit, and the
failure SHALL name the scenario and the target.

A build SHALL NOT fail for a target it did not attempt to cover. A target the
build did not cover SHALL be stated as such in the result view, and SHALL NOT be
presented as an outcome.

The two cases SHALL remain distinguishable. "The target was covered and produced
nothing" is the defect this requirement exists to catch; "the target was not
covered" is a fact about the build's scope, and reporting it as a failure — or
leaving it looking like a result — are the same error in opposite directions.

**Rationale:** the view already distinguishes four outcomes, and the one that
matters is the one nothing acts on. A cell that a reader cannot interpret is
indistinguishable from a cell that says "verified", which is the ambiguity a
committed class-count floor was introduced to remove. And the naive version of
this requirement is worse than none: a target covered by a different job would go
red for every build that did not run that job.

#### Scenario: A claimed target produces no result

- **Given** a scenario that claims two targets
- **And** a build that covers both
- **When** one of those targets produces no result for that build's commit
- **Then** the verification gates fail
- **And** the failure names that scenario and that target

#### Scenario: A target the build did not cover

- **Given** a scenario that claims two targets
- **And** a build that covers one of them and declares the other out of scope
- **When** the gates run
- **Then** the gates pass
- **And** the result view states that the un-covered target was not covered

#### Scenario: A target covered by a different job

- **Given** a scenario whose second target is covered by a job that does not run
- **When** the build that excludes that job runs its gates
- **Then** the gates do not report the missing target as a failure
- **And** the result view does not present it as an outcome either

#### Scenario: The gate is wrong and has to be caught

- **Given** the check is in place
- **When** a claimed target's result is withheld
- **Then** the check fails and names the scenario and target that lost it
- **And** restoring the result returns the check to passing
