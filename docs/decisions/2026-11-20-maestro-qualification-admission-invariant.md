---
title: "Maestro flow qualification — the gate admits a record only when the APK is the checkout's build"
date: 2026-11-20
status: accepted
tags: [maestro, qualification, gate, evidence]
issue: 3052
---

## Context

The qualification runner already refuses to run a flow against an APK that is
not the checkout's build, and it no longer accepts `--allow-stale-apk`. That is
the runner-side half of the rule.

The other half was missing. The gate (`scripts/check-maestro-flow-qualification.py`)
read the build identity of an attempt as `apk_commit_sha or commit` — one of
the two values was enough, and the two were never compared. So a record that
named two *different* builds, or only one of them, was admissible: the gate
checked that the attempts agreed with each other, not that the program they ran
was the program the change is about.

The runner-side check is a defence in depth, not the rule. The rule has to
hold for the record the gate admits, whoever produced that record — the
qualification runner, a hand-edited evidence file, a merge from a branch that
was qualified elsewhere.

## Idea

1. **Keep the gate lenient.** The record names the build it ran; the runner
   already refuses a stale APK, so a second check is redundant.
2. **Compare against the CI HEAD.** The gate requires the recorded build to be
   the commit the pipeline runs.
3. **Require both identities and require them to agree.** The record has to
   name the checkout it was qualified in *and* the build that ran on the
   device, and those two have to be the same build.

## Decision

**Option 3.**

`is_qualified()` in `scripts/check-maestro-flow-qualification.py` requires, for
every attempt:

- `commit` — the checkout the qualification was made in, and
- `apk_commit_sha` — the build id read from the device,

and refuses the record when either is missing, or when the two disagree:

- missing → `attempt N does not record both the checkout and the APK identity`
- disagreeing → `attempt N ran an APK that is not the checkout build (X vs Y)`

`record_issues()` in `scripts/maestro-qualify-flows.py` applies the same two
rules, so the runner says why a record is not admissible *before* it writes
the record, instead of leaving the gate to reject it later. Both sides apply
one implementation of the rule — `attempt_admission_issues()` in the gate
module — so the runner's refusal and the gate's admission cannot drift
apart; see the record contract ADR below.

## Rationale

- **The gate is the place where the rule has to hold.** A gate that trusts the
  producer of the evidence cannot enforce anything about the evidence: the
  producer is exactly the party a mistake (or a stale APK) comes from.
- **Comparing against CI HEAD would be wrong.** The qualification is made in
  the author's checkout; the pipeline builds a different commit by definition.
  Requiring the recorded build to be the CI HEAD would invalidate every
  qualification as soon as an unrelated file changed anywhere in the
  repository. The invariant is therefore *internal* to the record: the two
  identities the record carries have to name the same build.
- **Both identities are needed.** Either one alone can be satisfied by a
  record that names only what it was checked against. Two independent values
  that have to agree are a check, not a copy.
- **The runner keeps its own check.** Defence in depth: the runner refuses a
  stale APK before spending a device run, and the gate refuses a record that
  says one was used.

## Consequences

- A record written by an older runner — one that recorded only one of the two
  identities — is no longer admissible and has to be requalified. The runner
  in this repository writes both, so this affects hand-written and
  externally-produced records only.
- The rule is checked on the recorded values, so it holds regardless of who
  produced the record; it does not attest that the values are *true*. That
  boundary is documented separately: see the trust boundary ADR below.

## Links

- Issue: https://github.com/gazon1/sing/issues/3052
- Policy: `docs/testing/maestro-flow-qualification.md`
- Gate: `scripts/check-maestro-flow-qualification.py`
- Runner: `scripts/maestro-qualify-flows.py`
- Related: `docs/decisions/2026-11-20-maestro-qualification-apk-provenance.md`,
  `docs/decisions/2026-11-20-maestro-qualification-record-contract.md`,
  `docs/decisions/2026-11-20-maestro-qualification-evidence-trust-boundary.md`
