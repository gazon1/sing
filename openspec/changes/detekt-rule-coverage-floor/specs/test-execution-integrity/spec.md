# test-execution-integrity

## ADDED Requirements

### Requirement: REQ-8 The custom detekt rules are covered by a floor, and a floor is recorded before it is enforced

Coverage of `:detekt-rules` SHALL be enforced as a floor in that module's
`check`. The threshold SHALL be the smallest legitimate measurement, recorded as
a named constant together with the date and the command that produced it, and
SHALL never be lowered without a reason written next to it.

A positive test proves a rule fires. It does not prove its branches are
reachable, so a rule with one positive test and three guards can still ship a
guard that never runs. The floor is what keeps that visible.

The floor SHALL NOT be wired into the root kover aggregation, which covers the
product modules and is governed by `config/docs/coverage-baseline.txt`. The two
must stay separately diagnosable: a rule-coverage regression and a
product-coverage regression produce different failures and need different fixes.

**Rationale:** every custom detekt rule in this repository has at least one
positive test, added so that no rule can pass by never firing. That is a floor
on *existence*, not on *coverage*. Measured line coverage is 92% and nothing
enforces it, so the remaining 8% is a report printed by a recipe nobody is
required to run, about the rules that enforce every other rule.

#### Scenario: A new guard is added to a rule without a test

- **Given** the recorded rule-coverage floor
- **When** a guard is added to a custom detekt rule and left untested
- **Then** rule coverage falls below the floor
- **And** `:detekt-rules:check` fails, naming the rules that lost coverage

#### Scenario: The threshold is zero

- **Given** a rule-coverage threshold of 0, or no threshold at all
- **When** the guard that validates the configuration runs
- **Then** it fails
- **And** it names the missing or zero threshold

#### Scenario: Kover instruments nothing

- **Given** a Kover configuration that resolves no classes
- **When** rule coverage is measured
- **Then** the result is 0% covered
- **And** `:detekt-rules:check` fails against any non-zero floor

#### Scenario: Coverage improves

- **Given** the recorded floor is below current coverage
- **When** branch tests raise coverage
- **Then** the floor is raised in the same commit
- **And** the raise is not left for a later change, because a floor below the
      measurement stops being a ratchet
