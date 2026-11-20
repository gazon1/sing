---
title: "Maestro flow qualification — what is validated is what the attempts run"
date: 2026-11-20
status: accepted
tags: [maestro, qualification, gate, policy]
issue: 3052
---

## Context

The review round found three gaps in the same place: the boundary between
what the gate looks at and what the attempts read.

1. **Admission was ordered, not independent.** `scan()` looked at the
   qualification record first and returned on the first failure. A flow with a
   stale qualification record and a valid exception was reported as "not
   qualified" and the exception — the decision that actually admits the flow —
   was never read. The valid admission was unreachable behind the invalid
   one.
2. **A reference that resolves to nothing was silently dropped.** The
   validator refused a reference that leaves the qualification tree, but a
   reference to a file that does not exist (or to a directory) inside the tree
   was dropped as "not a dependency": the fingerprint never covered it, the
   attempt could not run it, and the qualification spent three runs on a
   failure the gate could have named before the first attempt.
3. **The working tree was validated, the snapshot was not.** The runner
   checked the references of the flow in the checkout, then copied the tree
   into the snapshot the attempts run. A reference that leaves the tree
   introduced between the check and the copy landed in the snapshot: the
   evidence would attest to content the gate never saw.

## Idea

1. **Evaluate the qualifications in a fixed order and return on the first
   failure.** Simple to read, and it makes a valid admission unreachable
   behind an invalid one.
2. **Evaluate both admissions independently.** A qualification admits, or an
   exception admits; neither one cancels the other, and the reasons of both
   are reported when neither holds.
3. **Validate the snapshot.** The copy is what the attempts read, so the copy
   is what has to be validated.

## Decision

**Options 2 and 3.**

- In `scan()`, a qualification and an exception are evaluated independently. A
  valid qualification admits the flow; otherwise a valid exception admits it;
  when neither holds, both reasons are reported.
- `dependency_issues()` refuses a reference that does not resolve to a file
  inside the qualification tree — a missing helper or a directory is a
  reference the tree cannot cover, like a path that leaves it.
- The runner validates the snapshot right after it is taken and before the
  first attempt. A flow that only the snapshot reaches is refused with
  `attempts_run: 0` and no record.

## Rationale

- **An admission is a decision, not a contest.** The rule says a flow is
  admitted by a qualification *or* by an exception. Reading them as a
  priority list makes a valid decision disappear behind an invalid one, which
  is not what the rule says.
- **The validator has to name everything the fingerprint cannot cover.** A
  reference to a missing file is content no fingerprint covers and no
  snapshot contains. Dropping it silently lets a flow that cannot run pass
  the validator.
- **What is validated has to be what runs.** Checking the tree and then
  running a copy of it leaves a window; closing it costs one call. The
  snapshot is the artifact the evidence is about, so it is the artifact that
  is validated.

## Consequences

- A flow with both a stale qualification and a valid exception is admitted as
  excepted, and the stale record is not a finding on its own. The evidence
  file keeps both records; only one of them has to hold.
- The gate reports two findings where it used to report one, when both the
  qualification and the exception fail. Both have to be fixed.
- The runner validates the tree twice: once before the copy (to refuse early,
  without copying the tree) and once on the copy (to refuse what the attempts
  would read).

## Links

- Issue: https://github.com/gazon1/sing/issues/3052
- Policy: `docs/testing/maestro-flow-qualification.md`
- Gate: `scripts/check-maestro-flow-qualification.py`
- Runner: `scripts/maestro-qualify-flows.py`
- Related: `docs/decisions/2026-11-20-maestro-qualification-tree-boundary.md`
