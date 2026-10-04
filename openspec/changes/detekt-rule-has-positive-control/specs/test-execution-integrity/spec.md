# test-execution-integrity

## ADDED Requirements

### Requirement: REQ-11 A lint rule cannot be added without a positive control

Every rule that this repository defines for its own lint pass SHALL have at
least one positive control: a fixture that the rule is required to flag. A rule
with no such control SHALL fail the verification gate, which SHALL name the rule
whose control is missing.

A positive control is what distinguishes a rule that works from a rule that
never runs. A rule that matches nothing satisfies every assertion that only
checks the rule stays quiet, so a suite of negative controls alone can be green
forever around a rule that has never once reported.

The gate SHALL recognise a positive control wherever it lives — a dedicated
per-rule suite or a shared suite that covers the remaining rules — and SHALL NOT
require a particular file name. A check that looks for one naming convention
reports a gap where none exists the moment a second convention is used, and a
check that matches less than intended is indistinguishable from a check with
nothing to match.

The gate's own failure mode SHALL be demonstrated before it is accepted: the
gate SHALL be shown to fail when a positive control is removed and to pass when
it is restored. A verification gate that has only ever reported success has not
been verified.

**Rationale:** a lint rule is an assertion about the codebase made by code that
no other check inspects. One such rule shipped in a state where it could not
report a finding for any input, and the tests written alongside it were
simultaneously failing. Every rule now has a positive control, but that is the
current state rather than an enforced property, and the first attempt to measure
the gap over-reported it by looking for one file-naming convention.

#### Scenario: A rule is added with a positive control

- **Given** a new lint rule and a fixture that it is required to flag
- **When** the verification gates run
- **Then** the check for positive controls passes
- **And** the rule's own suite reports the finding against that fixture

#### Scenario: A rule is added with no positive control

- **Given** a new lint rule and no fixture that it is required to flag
- **When** the verification gates run
- **Then** the check for positive controls fails and names that rule

#### Scenario: A positive control is provided by a shared suite

- **Given** a rule whose positive control lives in a suite shared with other
  rules rather than in a suite of its own
- **When** the verification gates run
- **Then** the check for positive controls passes

#### Scenario: The gate is wrong and has to be caught

- **Given** the check for positive controls is in place
- **When** one positive control is removed
- **Then** the check fails and names the rule that lost its control
- **And** restoring the control returns the check to passing
