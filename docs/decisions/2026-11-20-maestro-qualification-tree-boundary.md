---
title: "Maestro flow qualification — the tree it covers has to be the tree it runs in"
date: 2026-11-20
status: accepted
tags: [maestro, qualification, gate, policy]
issue: 3052
---

## Context

The qualification gate fingerprints a flow together with the helpers it runs,
and the runner executes the attempts against an immutable snapshot of the
`Maestro/` tree. Both halves were still narrower than the promise they make.

A fingerprint that covered only YAML helpers left out the helper *scripts* a
flow runs through `runScript` (`Maestro/helpers/scripts/*.js`). An edit to
such a script changes what the flow does, and the fingerprint did not move:
the gate kept admitting a flow whose behaviour had changed, and the record
named a fingerprint that said nothing about the script the attempts had run.

The reference resolution had a second hole. A flow could name an absolute
path, or a relative path that climbs out of the tree. Such content is outside
the fingerprint *and* outside the snapshot the attempts run, so an edit there
between two attempts went unnoticed — the failure mode the snapshot was
introduced to close, in a place the snapshot does not reach.

## Idea

1. **Fingerprint the YAML closure and leave scripts out.** Cheapest, and it
   certifies content the flow actually runs.
2. **Fingerprint every file the flow references, and refuse the flows that
   reference outside the tree.** The fingerprint then names the whole
   contract, and a flow that reads content nobody can attest to is refused
   instead of admitted on the strength of the part that can be seen.

## Decision

**Option 2.**

- The fingerprint covers every file a flow references, transitively: helper
  flows *and* helper scripts. The reference patterns accept `runFlow`,
  `file:` and `runScript` references, and the extensions are not restricted
  to YAML.
- A reference that leaves the tree — an absolute path, or a relative path
  that climbs out of it — is an **untracked dependency**. The gate reports
  the flow as `untracked dependency` and admits nothing for it: not the
  baseline, not a qualification record, not an exception. The runner refuses
  it before the first attempt and says why, so three attempts are not spent
  on evidence the gate would reject.
- The fingerprint definition changed inside the change that introduced the
  rule, so the baseline map was recomputed in the same branch: seven flows
  whose closure now includes a helper script got a new fingerprint. No flow
  or helper content changed — the diff of `Maestro/` in that branch touches
  `CONVENTIONS.md` and `README.md` only — and the frozen set and its pinned
  digest are untouched. The one-time write protection of `--init-baseline`
  stays in place for everything after this.

## Rationale

- **The fingerprint has to cover what the flow runs.** A helper script is
  content the qualification attests to exactly as much as a helper flow is;
  leaving it out made the fingerprint a statement about the YAML the flow
  reads, not about the flow.
- **Content outside the tree cannot be attested to, so it cannot be admitted.**
  Refusing the flow is the honest answer: the alternative is a record that
  claims three passing runs for a flow whose other half ran from somewhere
  nobody looked.
- **Refuse before spending the attempts.** Three runs on a device cost
  minutes; the refusal costs a message. The runner says so before it starts
  rather than after it has produced artifacts nobody can use.
- **Recomputing the baseline is not the same as growing it.** The baseline
  admits flows by fingerprint, and the seven recomputed fingerprints name
  the same content as before — the same files, hashed with a wider closure.
  The frozen set, which is what admits flows at all, did not change.

## Consequences

- A flow that references content outside the tree cannot be admitted until
  the reference is made relative to the repository. The gate names the
  offending reference, so the fix is visible in the message.
- A helper script edit now invalidates the qualification of every flow that
  runs it. That is the point: the script is part of the flow.
- The baseline map carries fingerprints that were computed with the wider
  closure. An older baseline cannot be read by the current gate, and the
  gate says which fingerprint it expects.

## Links

- Issue: https://github.com/gazon1/sing/issues/3052
- Policy: `docs/testing/maestro-flow-qualification.md`
- Prior decision: `docs/decisions/2026-11-20-maestro-qualification-anchors.md`
- Gate: `scripts/check-maestro-flow-qualification.py`
- Runner: `scripts/maestro-qualify-flows.py`
