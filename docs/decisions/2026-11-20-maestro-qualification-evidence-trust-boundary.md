---
title: "Maestro flow qualification — what the gate proves, and what the record attests"
date: 2026-11-20
status: accepted
tags: [maestro, qualification, gate, evidence]
issue: 3052
---

## Context

The qualification gate reads a record from
`config/docs/maestro-flow-qualification.yaml` and admits a flow on the
strength of it. The record says that a flow ran three times on a device, that
every attempt passed, and that the APK was the checkout's build.

A reviewer of that gate can reasonably ask: does the gate *check* that the
attempts happened? It does not. The gate reads a YAML file. It never opens the
JUnit XML, never reads `run-summary.json`, never talks to a device, and cannot
tell a record written by the qualification runner from a record typed by
hand. Everything the gate verifies is verified *about the record*, not about
the world it describes.

That is a real limitation, and it is easy to hide by using the wrong words.
Calling the YAML entry "proof that the flow passed three times" claims more
than the gate establishes: the gate establishes that a *record asserting that*
is present, consistent and current. The claim that the runs happened is
attested by whoever wrote the record, and it is checked by a human or agent
reviewing the pull request — not by the gate.

The alternative — making the gate independently verify the runs — would mean
re-running every flow on every gate invocation, on a device the CI runner does
not have. That is a different gate (a test-suite gate), not this one.

## Idea

1. **Make the gate an attestor.** Treat the record as an attestation the
   gate checks for internal consistency and currency; say so in the wording.
2. **Make the gate a verifier.** Have the gate read the per-attempt artifacts
   (JUnit, `run-summary.json`) and re-derive the outcome itself.
3. **Keep the gate as it is and say nothing.** The record has always been
   trusted; naming the boundary is a documentation nicety.

## Decision

**Option 1, with the boundary written down.**

The gate keeps its job — it verifies what is verifiable from the record and
from the repository, and refuses everything else. The wording of the policy
and of the gate's own output names the boundary explicitly, so the record is
never described as proof of a device run.

What the gate verifies, on the record:

- the fingerprint matches the flow as it is in the working tree (the flow and
  everything it runs, transitively),
- the record carries at least `required_attempts` attempts, and every one of
  them is a `pass`,
- the attempts share one environment — the device itself (`kind`, `api`,
  `device`, `serial`), not only its model,
- the attempts share one build, and that build is both the checkout the
  qualification was made in and the APK that ran on the device (two
  identities that have to agree),
- the attempts share one engine version, and that version is *recognised* as a
  version by the same recognition the runner applies
  (`emulator.maestro._engine`) — a record cannot name "Maestro version
  unavailable" as the engine that ran the flow.

What the gate does not verify, and what it therefore never claims:

- that the attempts were executed on a device at all,
- that the outcomes in the record match the JUnit reports and
  `run-summary.json` the attempts produced,
- that the recorded identities are the identities of the real device and the
  real build.

The fact of the run is established by the qualification runner, which is the
only supported way to produce a record — but *established* is the runner's
word, not the gate's. The gate never sees the run, and neither does a
reviewer: the per-attempt artifacts (the JUnit reports, `run-summary.json`,
the environment dumps) live under `build/maestro-qualification/` and are not
committed, so the outcomes in the record cannot be re-derived from the pull
request. What review adds is a reading of the record against the diff — this
flow changed here, the record's fingerprint matches this flow, the device and
the build are named — and a human's judgement about whether the record is
credible. That step is a process, not a property the tooling enforces:
nothing in the repository makes a reviewer re-run the flow.

So the boundary is trust, and it is trust in the author of the record. The
gate's job is to make that trust as narrow as it can be: it refuses every
record that is internally inconsistent or stale, so the reviewer's remaining
question is only "did these attempts really happen, on this device, with this
build" — a question about the world — and not "is this a coherent
qualification" — a question about the file. The runner's own refusal rules —
no stale APK, no unrecognised engine version, no untracked dependency, an
immutable snapshot — are what make the record worth trusting in the first
place. The gate is the *last* line, not the only line.

## Rationale

- **A gate that claims more than it checks is worse than one that claims
  less.** The evidence file is the mechanism the whole rule rests on. If it
  is read as proof, then a hand-edited file is proof, and the rule is
  decoration.
- **Option 2 is a different product.** Re-deriving the outcome from artifacts
  means the gate needs the artifacts and a device; artifacts live under
  `build/` and are not committed, so the gate would have nothing to read in
  CI. The attempt outcomes are re-derived by the runner at the moment of the
  run, which is where the artifacts exist.
- **The checks that are in the gate are the ones that catch the realistic
  mistakes.** A fingerprint that drifted (the flow changed), attempts on two
  devices, attempts on two builds, an APK that is not the checkout's build, an
  engine version nobody recognised — these are the ways a qualification goes
  wrong without anybody editing the file on purpose, and every one of them is
  refused by the gate.
- **The trust the gate cannot remove is trust in the author, and it has to be
  named as such.** A reviewer who believes the record was produced by the
  runner needs to know that this is a belief, not a check: the artifacts are
  not committed, so nothing in the pull request re-derives the outcomes.
  Saying so does not weaken the rule — it locates it. The alternative is a
  document that claims the gate checks the run, and a reviewer who believes
  it and reads nothing.
- **Option 3 leaves the impression to the reader.** The gap between what the
  gate checks and what it is taken to check is exactly where an unreviewed
  evidence file would be edited. Naming the boundary is what makes the
  review step part of the rule instead of an extra.

## Consequences

- The policy and the docs say "record", "admission", "evidence", and never
  "proof that the flow passed" for the YAML entry. The verdict of a run is
  the runner's, and the record carries it.
- A reviewer has to read a qualification record in a pull request as an
  attestation, and to check it against the diff: the flow changed, the
  record's fingerprint matches the changed flow, the device and the build are
  named. That is a small job, and it is the job the gate cannot do.
- The boundary is trust in the author of the record, and it is a process step,
  not an enforced property: no tooling in this repository makes a reviewer
  re-run the flow, and no gate can. Publishing the per-attempt artifacts as
  CI artifacts would narrow the trust, and is a separate piece of work.
- The gate stays cheap and hermetic: it runs in CI without a device, and it
  fails when the record is stale, inconsistent or claims the wrong build.

## Links

- Issue: https://github.com/gazon1/sing/issues/3052
- Policy: `docs/testing/maestro-flow-qualification.md`
- Gate: `scripts/check-maestro-flow-qualification.py`
- Runner: `scripts/maestro-qualify-flows.py`
- Related: `docs/decisions/2026-11-20-maestro-qualification-admission-invariant.md`,
  `docs/decisions/2026-11-20-maestro-qualification-record-contract.md`,
  `docs/decisions/2026-11-20-maestro-qualification-device-identity.md`,
  `docs/decisions/2026-11-20-maestro-qualification-version-prerequisite.md`
