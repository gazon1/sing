---
title: "Maestro flow qualification — the frozen set is anchored outside the file it guards"
date: 2026-11-20
status: accepted
tags: [maestro, qualification, gate, policy]
issue: 3052
---

## Context

Issue #3052 introduced the qualification gate for Maestro flows: a flow may
only enter a blocking automated scope if it is baselined, qualified by a
bounded repeat run, or excepted with evidence. The baseline is
`config/docs/maestro-flow-qualification-baseline.yaml`, and it originally
carried both halves of the admission in one file: the map of
flow → fingerprint, and the list `initial_flows` — the flows that were in a
blocking scope when the rule was introduced, the *frozen set*.

The gate refused a flow that was in the map but not in `initial_flows`, which
is the right refusal, but it could not see the case that mattered: both
halves in one file are one edit apart. A new flow added to the map and to
`initial_flows` at the same time reads as a flow that was there when the rule
was introduced, and the gate admits it — a flow nobody ever ran, on the
strength of a fingerprint the contributor computed themselves. `--init-baseline`
made it worse: it rebuilt both halves from the current catalogue, so the tool
that was supposed to establish the baseline could also grow it.

Two more weaknesses showed up in the same review:

- the qualification record carried a fingerprint computed *after* the attempts,
  so a flow edited while it was being qualified produced a record claiming
  three passing runs for a version nobody ran;
- an invalid `required_attempts` in the evidence file silently fell back to
  three, so a typo decided what counted as a qualification.

## Idea

Three ways to keep the trusted set from being edited into existence.

1. **Keep both halves in the baseline file and rely on review.** Cheapest, and
   the thing that failed.
2. **Derive the frozen set from git history** — the catalogue as it was at the
   commit that introduced the rule. Tamper-proof in principle, but CI checks
   out with a shallow history in some jobs, so the gate would need a fallback
   that is weaker than the thing it replaces.
3. **Split the artifacts and pin the trusted one.** The frozen set moves into
   its own file, and the gate holds a digest of it. The baseline keeps only
   fingerprints.

## Decision

**Option 3.** The frozen set and the baseline are separate artifacts, and the
frozen set is pinned by a digest that lives in the gate.

- `config/docs/maestro-flow-qualification-initial.yaml` holds the frozen set.
  `FROZEN_SET_DIGEST` in `scripts/check-maestro-flow-qualification.py` holds
  its digest. The gate refuses to run when the artifact does not match, so
  adding a flow to the frozen set takes a source change — a diff that says in
  plain sight that flows are admitted without being qualified.
- `config/docs/maestro-flow-qualification-baseline.yaml` holds fingerprints
  only. A baseline that still carries `initial_flows` is refused: a file that
  names its own trusted set can be edited once to admit a flow.
- `--init-baseline` writes the fingerprint map for the frozen set only,
  refuses to overwrite an existing baseline, and never touches the frozen set
  or its pin. A changed flow is requalified, not re-baselined.
- The runner takes the fingerprint before the first attempt and again after the
  last one, and writes a record only when the two agree.
- An invalid `required_attempts` is a configuration error. A missing one means
  three attempts.

## Rationale

- **The pin is where the trust lives, and it is not in the file it guards.** A
  frozen set in the baseline could be grown by editing the baseline; a frozen
  set pinned by a digest in the gate cannot be grown without a change to the
  code that enforces it. That is the difference the review asked for.
- **The failure mode of a wrong pin is loud, not quiet.** A mismatch stops the
  gate before it looks at a single flow, and the message says what to do.
  Nothing is admitted by accident.
- **The digest belongs in the gate, not in a third file.** A third file next
  to the artifact would be edited by the same hand in the same commit, and the
  diff would show a data file rather than a decision. In the gate, the change
  reads as what it is.
- **Git history was rejected on operability.** The shallow checkout is the
  normal case in some CI jobs, and a rule whose strength depends on the depth
  of a clone is a rule that quietly weakens itself.
- **The record has to name the flow that ran.** A fingerprint taken after the
  attempts describes the tree, not the run; a flow edited mid-run would be
  certified by a record that never applied to it.
- **A policy that cannot be parsed is not a policy.** Falling back to three
  attempts on a typo turns a configuration mistake into a silent change of
  what counts as a qualification.

## Consequences

- The admission now rests on two files instead of one, and the second one is a
  list of 127 flow names. The digest in the gate has to be updated whenever the
  frozen set legitimately changes — deliberately, in the same change.
- `--init-baseline` is no longer a way to rebuild the baseline from the
  current catalogue. It is a one-time bootstrap for a fresh checkout, and it
  refuses to run over an existing baseline.
- A flow in a blocking scope that is neither in the frozen set nor qualified
  nor excepted is reported as *unqualified*; a flow in the frozen set whose
  fingerprint changed is reported as *stale baseline*. The two were one
  message before.
- The runner refuses to write a record when the flow changed under it. The
  attempts are preserved; the flow is qualified again, against the version in
  the tree.

## Links

- Issue: https://github.com/gazon1/sing/issues/3052
- Policy: `docs/testing/maestro-flow-qualification.md`
- Gate: `scripts/check-maestro-flow-qualification.py`
- Runner: `scripts/maestro-qualify-flows.py`
