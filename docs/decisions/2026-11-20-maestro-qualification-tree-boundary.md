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

The tree the gate covers was still named ambiguously. The fingerprint and the
reference validator walked the whole repository, while the snapshot copied
only `Maestro/`. A reference that stayed inside the repository but left
`Maestro/` — `../../../scripts/helper.js` from a flow — was accepted by the
validator and folded into the fingerprint, and was simply absent from the
snapshot the attempts ran. The gate would have admitted the flow and the
attempt would have failed on a file the qualification never covered: the
validator and the snapshot disagreed about what the tree is.

## Idea

1. **Fingerprint the YAML closure and leave scripts out.** Cheapest, and it
   certifies content the flow actually runs.
2. **Fingerprint every file the flow references, and refuse the flows that
   reference outside the tree.** The fingerprint then names the whole
   contract, and a flow that reads content nobody can attest to is refused
   instead of admitted on the strength of the part that can be seen.
3. **Copy the whole repository into the snapshot.** The snapshot would cover
   everything a reference can reach, and the boundaries could stay wide.
4. **Name the tree once and let the validator, the fingerprint and the
   snapshot agree on it.** The qualification tree is `Maestro/`; a reference
   that leaves it is refused.

## Decision

**Option 2, with the tree of option 4: the qualification tree is `Maestro/`.**

- The fingerprint covers every file a flow references, transitively: helper
  flows *and* helper scripts. The reference patterns accept `runFlow`,
  `file:` and `runScript` references, and the extensions are not restricted
  to YAML.
- A reference that leaves the tree — an absolute path, a relative path that
  climbs out of the repository, or a path that stays in the repository but
  leaves `Maestro/` — is an **untracked dependency**. The gate reports the
  flow as `untracked dependency` and admits nothing for it: not the
  baseline, not a qualification record, not an exception. The runner refuses
  it before the first attempt and says why, so three attempts are not spent
  on evidence the gate would reject.
- A reference that stays inside the tree but does not resolve to a file — a
  missing helper, a directory — is an untracked dependency as well. It names
  content no fingerprint can cover and no snapshot contains, so dropping it
  would let a flow that cannot run pass the validator.
- **The qualification tree is `Maestro/`.** The reference validator, the
  fingerprint and the snapshot now name the same tree, so a reference that
  resolves inside it is covered by all three, and one that resolves outside
  it is refused by the gate before the attempts are spent. Copying the whole
  repository into the snapshot was the alternative; it was rejected because
  the tree a flow runs in is the flow tree, and the snapshot exists to freeze
  that tree, not to mirror the checkout.
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

- A flow that references content outside `Maestro/` cannot be admitted until
  the reference is moved inside it. The gate names the offending reference,
  so the fix is visible in the message.
- A helper script edit now invalidates the qualification of every flow that
  runs it. That is the point: the script is part of the flow.
- The baseline map carries fingerprints that were computed with the wider
  closure. An older baseline cannot be read by the current gate, and the
  gate says which fingerprint it expects.

## Links

- Issue: https://github.com/gazon1/sing/issues/3052
- Policy: `docs/testing/maestro-flow-qualification.md`
- Prior decision: `docs/decisions/2026-11-20-maestro-qualification-anchors.md`
- Related: `docs/decisions/2026-11-20-maestro-qualification-admission-precedence.md`
- Gate: `scripts/check-maestro-flow-qualification.py`
- Runner: `scripts/maestro-qualify-flows.py`
