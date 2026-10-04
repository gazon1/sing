# test-execution-integrity

## ADDED Requirements

### Requirement: REQ-6 A suppression baseline is a ceiling, not a queue

The committed detekt suppression count SHALL be enforced as a ceiling. CI SHALL
compare the finding count in the current detekt baselines against the committed
count and SHALL fail when it is higher, naming the delta and the rules that grew
the most.

Lowering the ceiling SHALL require a reason recorded in the committed file, in
the same way as REQ-5's flaky baseline. A tool that regenerates the count SHALL
be able to raise it, and SHALL refuse to lower it without that reason.

The gate SHALL report the magnitude — current count, floor, and delta — not a
bare boolean, and SHALL include a positive control so that it cannot pass by
scanning nothing.

**Rationale:** `config/detekt/baseline-shared.xml` holds 413 suppressions and was
observed growing 407 → 408 → 413 across recent branches, with no gate and no
signal. The three largest clusters are `BackingPropertyNaming` (53, which
contradicts the canonical ViewModel pattern that mandates `_state`),
`PackageName` + `PackageNaming` (86), and `LongMethod` (39). A baseline that
grows on its own is a record of what nobody has fixed yet, presented as though it
were a record of what was agreed.

#### Scenario: A new violation is baselined

- **Given** the committed ceiling is 413
- **When** a change introduces a violation that is added to the baseline, making 414
- **Then** the gate exits non-zero and reports `414 > 413` with the delta

#### Scenario: Fixing violations raises the floor

- **Given** the committed ceiling is 413
- **When** a change removes 12 suppressions, making 401
- **Then** the gate passes, and the commit that made the change is expected to
  lower the recorded ceiling to 401

#### Scenario: The floor is lowered with no reason

- **Given** the recorded count has no reason recorded next to it
- **When** the gate runs
- **Then** it exits non-zero — an unexplained decrease is indistinguishable from
  a baseline regenerated against a partial build

### Requirement: REQ-7 A measured floor may not be lowered without a reason

The coverage floor (`config/docs/coverage-baseline.txt`) and the executed-test
floor (`config/docs/test-runs-baseline.txt`) SHALL only move downward when the
regenerating command is given an explicit reason, which SHALL be recorded in the
baseline file next to the value.

Both files state this rule in a comment today. This requirement makes it
executable: the rule exists in prose, and prose has never stopped anyone from
running `--update-baseline` against a build that ran half the suite.

**Rationale:** The coverage floor was once recorded from a `-Ptest.tags=fast,slow`
run while a plain local run executes a strict subset — the same defect that
produced the stale-baseline incident documented in
`2026-10-04-measurement-integrity`. Recording the CI number as the floor makes
every local run read as a regression; regenerating from a broken run makes every
future run pass against a number that was never measured. Both directions are
silent, which is the property that has to be removed.

#### Scenario: Regenerating from a run that measured less

- **Given** a coverage report produced by a partial run measures 12.0% against a
  floor of 25.0%
- **When** `check-coverage.py --update-baseline` is run without a reason
- **Then** it exits non-zero and leaves the floor unchanged

#### Scenario: Legitimately lowering the floor

- **Given** code is removed and the measured coverage genuinely falls
- **When** `--update-baseline` is run with a reason naming the removal
- **Then** the floor is written, the reason is recorded beside it, and the gate
  passes on subsequent runs
