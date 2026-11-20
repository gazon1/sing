---
title: "Maestro flow qualification — one record contract, held in the gate"
date: 2026-11-20
status: accepted
tags: [maestro, qualification, gate, evidence]
issue: 3052
---

## Context

Two components judge a qualification record.

The gate (`scripts/check-maestro-flow-qualification.py`) admits a flow on the
strength of the record: it reads the YAML and decides whether the record is
current, complete and coherent.

The runner (`scripts/maestro-qualify-flows.py`) produces the record: it runs
the attempts and, before it writes one, it asks whether the record it is about
to write would be admissible.

Both were answering the same question — *what has a record to record for a
qualification to count?* — with their own code. The rules were written twice:
the documented environment, the device, the checkout and APK identities, the
engine version, and the "one environment, one build, one version" agreement
across the attempts. The two copies already differed in detail, and nothing
in the repository kept them together.

Two gaps came out of that duplication.

- **A recorded version was checked for presence, not for being a version.**
  The gate asked whether the attempt recorded *something* as the Maestro
  version and whether the attempts agreed with each other. A record naming
  `not-a-version` was admissible — while the runner, which reads the version
  with a strict recognition, would never have written such a record. The gate
  promised "one recognised engine version" and did not hold it.
- **A rule that drifts is a rule the gate no longer holds.** With two copies
  of the contract, the copy the gate applies is the one that matters, and
  nothing tied the runner's refusal to it.

## Idea

1. **Keep both copies, and test them against each other.** Two
   implementations, one test that says they agree.
2. **Put the contract in one place, and let both ask for it.** The rules
   live in the gate module; the runner asks the gate module what it would
   refuse.
3. **Weaken the promise.** Stop claiming the version is *recognised*, and
   claim only that it is present.

## Decision

**Option 2.**

The rules a record has to satisfy live in the gate module, once:

- `attempt_admission_issues(attempt, index)` — what one attempt has to
  record: a documented environment (kind/api), the device it ran on
  (serial), *both* the checkout identity and the APK identity, with the two
  in agreement, and a Maestro version that is a version.
- `record_consistency_issues(record)` — what the record has to agree on
  across its attempts: one environment, one build identity, one engine
  version.
- `is_recognised_version(value)` — whether a recorded version is a version
  the engines recognise, read with `emulator.maestro._engine`, the same
  recognition the runner reads the engine's version with.

`is_qualified()` (the gate) and `record_issues()` (the runner) both apply
those functions. The runner already loads the gate module, so the shared
contract costs it nothing, and the two can no longer disagree about what a
record has to say.

A recorded version is now checked for being a version, not for being present:
`not-a-version`, `Maestro version unavailable` and `Maestro 1.39.0` are all
refused, and only a version number (`1.39.0`, `2.10.0`) is accepted — the
same shape the runner writes.

## Rationale

- **One question, one implementation.** The rules answer one question
  ("what makes a record a qualification?"). Two copies of an answer are two
  answers, and the one that decides admission is the one that counts.
- **The gate is where the promise has to hold.** The runner's refusals are
  advice; the gate's refusals are the rule. A rule that only the producer
  applies is not a gate.
- **Failing closed is cheap here.** A record that names a version nobody
  recognised is refused with a clear reason, and the fix is to re-run the
  qualification — three device runs, which is what a qualification costs
  anyway.
- **Option 1 leaves the drift in place.** A test that says the two agree
  fails on the day they do not, and it does not say which one is right.
- **Option 3 would leave the ADR promising more than the code holds.** The
  version is in the record precisely because the evidence has to name the
  build that ran the attempt; "some string was written in the version
  field" does not name a build.

## Consequences

- A record written before this change that carries something other than a
  version number is no longer admissible, and has to be requalified. Records
  written by the runner in this repository carry version numbers already.
- The gate and the runner now share a contract that the tests pin: a record
  the runner refuses is a record the gate refuses, and the two say the same
  thing about it.
- The runner's own copy of the rules is gone; the gate module is the only
  place a rule about a record lives.

## Links

- Issue: https://github.com/gazon1/sing/issues/3052
- Policy: `docs/testing/maestro-flow-qualification.md`
- Gate: `scripts/check-maestro-flow-qualification.py`
- Runner: `scripts/maestro-qualify-flows.py`
- Recognition: `scripts/src/emulator/maestro/_engine.py`
- Related: `docs/decisions/2026-11-20-maestro-qualification-admission-invariant.md`,
  `docs/decisions/2026-11-20-maestro-qualification-strict-version.md`,
  `docs/decisions/2026-11-20-maestro-qualification-evidence-trust-boundary.md`
