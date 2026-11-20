---
title: "Maestro flow qualification — the baseline map is pinned in the gate"
date: 2026-11-20
status: accepted
tags: [maestro, qualification, gate, policy]
issue: 3052
---

## Context

The frozen set was already anchored outside the file it guards: it lives in
`config/docs/maestro-flow-qualification-initial.yaml` and is pinned by
`FROZEN_SET_DIGEST` in the gate. The baseline map — flow → fingerprint — was
not.

The baseline is what actually admits a flow. A flow is baselined when it is
in the frozen set *and* the map holds its current fingerprint, so editing the
map is editing the admission. Changing a fingerprint in the map to the value
the flow has now admits a changed flow without anybody qualifying it, and the
edit is a one-line change to a data file that reads like a data change.

The review round asked for the same treatment the frozen set already had.

## Idea

1. **Trust review of the data file.** The map is a data file; a wrong
   fingerprint is visible in the diff and the reviewer catches it. Cheap, and
   it assumes every reviewer reads 127 hashes.
2. **Hash the map and store the hash next to it.** A third artifact next to
   the file it guards is edited by the same hand in the same commit, and the
   diff shows a data file rather than a decision.
3. **Pin the map by a digest kept in the gate**, the way the frozen set is
   pinned. The pin lives in the code that enforces it.

## Decision

**Option 3.** `BASELINE_DIGEST` in `scripts/check-maestro-flow-qualification.py`
holds the digest of the baseline map, and the gate refuses to run when the
artifact does not match it.

- The digest covers the whole map — every flow name and its fingerprint —
  sorted, so it does not depend on the order the map is written in.
- A mismatch stops the gate before it looks at a single flow, with a message
  that says what to do. Nothing is admitted by accident.
- `python3 scripts/check-maestro-flow-qualification.py --print-baseline-digest`
  prints the digest of the map in the checkout.
- `--init-baseline` prints the digest of the map it wrote, so the pin can be
  updated in the same change.

## Rationale

- **The pin is where the trust lives, and it is not in the file it guards.**
  This is the same argument that moved the frozen set out of the baseline; the
  map needs it for the same reason.
- **The failure mode is loud.** A mismatch is a configuration error the gate
  names, not a flow that quietly slips through.
- **One mechanism, not two.** The frozen set and the baseline are two artifacts
  guarding one rule, and both are pinned the same way — one idea, one place to
  look.
- **A wrong pin is a reviewed source change.** Changing the digest is a diff
  in the gate that says, in plain sight, that flows are admitted without being
  qualified.

## Consequences

- The gate now refuses to run when the baseline does not match its pin, so a
  legitimate change of the map (a recomputed fingerprint after a change of the
  fingerprint definition) is a source change too: the map and the pin move
  together, and the diff shows both.
- Two pins have to be updated when both artifacts change. That is the cost of
  the property, and it is paid in review, not in CI.

## Links

- Issue: https://github.com/gazon1/sing/issues/3052
- Policy: `docs/testing/maestro-flow-qualification.md`
- Gate: `scripts/check-maestro-flow-qualification.py`
- Related: `docs/decisions/2026-11-20-maestro-qualification-anchors.md`
