---
title: "Maestro flow qualification — the attempt count has a floor in the gate"
date: 2026-11-20
status: accepted
tags: [maestro, qualification, gate, evidence]
issue: 3052
---

## Context

`required_attempts` lives in `config/docs/maestro-flow-qualification.yaml`, and
both the runner and the gate read the policy from there. The file is evidence:
it is edited as data, and a change to it does not say what it does.

The rule this mechanism enforces is that a flow enters a blocking scope on the
strength of a **repeated** run. A policy file that said `required_attempts: 1`
qualified a flow on a single green run, and nothing in the tree showed it: the
gate read the file, honoured it, and admitted a flow on one attempt. The
mechanism kept its shape and lost the property it exists for.

## Idea

1. **Keep the policy in the file.** The file stays the place a person sets the
   attempt count; the gate trusts it.
2. **Floor the attempt count in the gate.** The file may ask for more attempts
   than the floor, never for fewer.
3. **Refuse the file outright.** A policy below the floor is a configuration
   error, like a non-integer value already is.

## Decision

**Options 2 and 3.**

`scripts/check-maestro-flow-qualification.py` holds `MIN_REQUIRED_ATTEMPTS = 3`.
`read_evidence()` refuses a policy below the floor with the same kind of
configuration error it already raises for a value that is not a positive
integer. A file may ask for more attempts than the floor — the floor is a
floor, not a ceiling — and the runner writes `MIN_REQUIRED_ATTEMPTS` into a new
evidence file rather than a literal, so the two cannot drift apart.

## Rationale

- **A floor in the file is not a floor.** The file is data, edited without a
  diff anybody reads; the gate is source, reviewed as source. The thing the
  rule protects — repetition — must not be one keystroke away from being off.
- **Refusing beats honouring.** The gate already refuses a non-integer policy
  instead of silently rounding it down to three. A policy of one attempt is the
  same class of problem: a value the gate cannot honour, not a smaller
  requirement it should apply.
- **The runner and the gate share one number.** The runner reads the floor
  from the gate module, so the value a new evidence file is seeded with and the
  value the gate enforces are the same constant.

## Consequences

- An evidence file that says `required_attempts: 1` is now a configuration
  error the gate reports, not a smaller requirement it honours. Changing it to
  3 is a visible edit in the file.
- A stricter policy stays possible: a file that asks for 5 attempts is honoured,
  and the flow needs five.

## Links

- Issue: https://github.com/gazon1/sing/issues/3052
- Policy: `docs/testing/maestro-flow-qualification.md`
- Gate: `scripts/check-maestro-flow-qualification.py`
- Runner: `scripts/maestro-qualify-flows.py`
- Related: `docs/decisions/2026-11-20-maestro-qualification-version-prerequisite.md`
