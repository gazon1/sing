---
title: "The qualification's currency is the flow's fingerprint, not the application build"
date: 2026-11-25
status: accepted
tags: [maestro, qualification, gate, policy]
issue: 3052
---

## Context

The gate `scripts/check-maestro-flow-qualification.py` admits a flow to a
blocking automated scope when the flow's fingerprint — the flow and the
helpers it runs, transitively — matches a record in
`config/docs/maestro-flow-qualification.yaml`. The record carries, per
attempt, the checkout the qualification ran in (`commit`) and the build that
ran on the device (`apk_commit_sha`); the gate refuses a record whose two
values name two different builds.

A review of PR #3130 asked for one more rule: that the recorded build has to
be the build of the *current* checkout, so that a qualification stops being
valid once the application code changes. The reasoning behind the ask is
sound on its own terms: the fingerprint covers the `Maestro/` tree only, so
an application-code change leaves every fingerprint untouched, and a flow can
stay admitted on the strength of three passes against an older APK.

The rule is not in the gate. This entry records why it is not, and what the
gate does instead — the question is asked once here rather than at every
review of a change that touches the qualification machinery.

## Idea

1. **Bind the admission to the current build.** Refuse a record whose
   `commit` is not the checkout the gate runs in.
2. **Bind the admission to the fingerprint, and name the program in the
   record.** The fingerprint says which flow content the record is about; the
   record says which build certified it.
3. **Leave the gate as it is, and say so.** The rule stays where it is, and
   the reason is written down where a reviewer will meet it.

## Decision

**Option 2, with option 3's write-up.**

The currency of a qualification is the fingerprint of the flow it is about.
The build identity in the record is the *name of the program the attempts
certified*, not a claim that the record is about every later build. The gate
holds the fingerprint, holds the record's internal consistency (one
environment, one build, one recognised engine version), and does not compare
the recorded build with the checkout it runs in.

## Rationale

- **The gate is a static check, and the build under test is not one of its
  inputs.** It runs from `scripts/ci/static-gates.sh` with no device and no
  APK. The SHA it could compare against is the checkout's HEAD — the build of
  the pull request — while the record names the build the flow was *run*
  against. The comparison would be between a record about one program and a
  SHA about another, and it would fail for every record the moment the
  branch moved.
- **The baseline says the same thing already.** Fifty-one flows are admitted
  by `config/docs/maestro-flow-qualification-baseline.yaml`, with no build
  identity at all: a flow that was in a blocking scope when the rule was
  introduced stays admitted as long as its content does not change (ADR
  `2026-11-20-maestro-qualification-baseline-pin`). A build-bound admission
  would contradict that on the same day: the same flow admitted today and
  refused tomorrow, depending on what else the repository changed.
- **An application regression is caught where it belongs.** The admitted flow
  keeps running in the blocking suite — `.github/workflows/e2e.yml` runs the
  smoke suite on every push to `main` and the full suite every night — so a
  flow that an application change breaks is red in the suite, not silently
  green. The qualification is an *admission* rule: it answers "does this flow
  pass repeatedly", not "does this flow still pass after somebody else
  changed the application".
- **The record is not vague about the program it certified.** Every attempt
  names the checkout and the APK's build id, the two have to agree, and the
  qualification runner refuses to write a record from an APK that is not the
  checkout's build (ADR `2026-11-20-maestro-qualification-apk-provenance`).
  A reviewer reading a record sees which program the three passes are about.
- **The alternative's cost is the rule's cost.** Every application-code change
  on `main` would invalidate every qualification, and each invalidated flow
  would need three device runs before it could be admitted again. That is a
  rule about merge cadence rather than about the quality of a flow, and it
  would put the gate red on most merges.

## Consequences

- A qualification survives an application-code change. What it attests is
  exactly this: this flow, in this content, passed three times in one
  documented environment against the build the record names.
- The application-build case is not hidden — it is named in the record, and a
  reviewer who needs the currency of a particular record has it.
- A pull request that changes application code and wants the affected flows
  re-qualified asks for it: the flows' fingerprints do not move on their own,
  and the re-qualification is a device run, not a gate decision.
- A reviewer who wants build-bound admissions has to bring it as a policy
  change, with the cost above named. It is a decision, not a defect in the
  gate.

## Links

- Issue: https://github.com/gazon1/sing/issues/3052 (the qualification rule)
- Pull request: https://github.com/gazon1/sing/pull/3130
- Policy: `docs/testing/maestro-flow-qualification.md`
- Gate: `scripts/check-maestro-flow-qualification.py`
- Related: `docs/decisions/2026-11-20-maestro-qualification-tree-boundary.md`,
  `docs/decisions/2026-11-20-maestro-qualification-apk-provenance.md`,
  `docs/decisions/2026-11-20-maestro-qualification-baseline-pin.md`
