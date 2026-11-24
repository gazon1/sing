---
title: "The ratchet's corpus block is a checked claim, not metadata"
date: 2026-11-24
status: accepted
tags: ["testing", "traceability", "gates"]
---

# The ratchet's corpus block is a checked claim, not metadata

## Context

`config/docs/traceability-ratchet.json` carries three floors — `holes`,
`dark_scenarios` and `unreachable_cells` — and one more block that is not a floor:
`corpus`, with `specs`, `claimed_cells` and `carriers`. The block records the size of
the corpus the floors were measured against, so a reader of a diff sees the matrix
size next to the numbers that constrain it.

It was also a second, hand-maintained copy of the matrix's header, and it rotted
silently: on `main` the block said `133 / 260 / 103` while the tree measured
`141 / 161 / 92` — main's own committed matrix header read
`141 scenarios · 92/161 claimed cells automated`. Every registered gate stayed green
the whole time, because no gate read the block. A copy that no gate reads is not a
claim; it is a number that drifts until a reader stops believing it.

The review cycle that produced this decision also found two further problems with
the first attempt at checking the block:

- The exit code was `CLEAN if (grew and accept_growth) else VALIDATION_FAILURE`, so a
  floor file with a growing metric **and** a stale corpus printed the corpus failure
  and still exited 0. A gate that prints a failure and exits 0 is not a gate.
- The block's shape was never validated: `"corpus": null` (or a string, or a number)
  made `isinstance(corpus, dict)` false, so the gate proceeded as if no block
  existed; a missing field was skipped the same way; and a non-numeric value raised an
  uncaught `ValueError` where the gate's own convention is a readable error.
- The denominator was defined twice: the gate and the matrix renderer each built
  their own list of live obligation cells, so "the definitions are the matrix's own"
  was only half true — the predicate was shared, the aggregation was not.

## Idea

- Keep the block, and make it a claim the gate **checks**: the three numbers are
  recomputed from the same traceability package that renders the matrix, and a
  mismatch fails the gate.
- Use the matrix's own definitions, defined once, on `Coverage`:
  `specs` = the number of scenario rows, `claimed_cells` = the number of live
  obligation cells (the matrix's denominator — a retired row is not an obligation),
  `carriers` = the number of links the scanner resolved.
- Keep the block optional, and make a block that is present but broken a readable
  error rather than a skipped check: a claim that is never compared is the failure
  the check exists to catch.
- Decide the escape hatch independently of the claim: `--accept-growth` covers the
  commit that knowingly opens holes, and does not cover a number the gate could not
  compare.

## Decision

- `scripts/check-traceability-ratchet.py` recomputes `specs`, `claimed_cells` and
  `carriers` and fails when `config/docs/traceability-ratchet.json` disagrees.
- The definitions live on `Coverage.obligation_cells()`
  (`scripts/src/traceability/coverage.py`), and both the matrix renderer
  (`scripts/src/traceability/render.py`) and the gate call it, so the renderer and the
  gate cannot count different corpora while both stay green.
- The block is optional: a floor file without one is still a floor file. A block that
  is there must be an object with all three keys and integer values; anything else is
  an `ERROR:` message with exit code 1.
- `--accept-growth` excuses a metric that grew. It does not excuse a stale corpus.
  The two conditions are independent, and the exit code decides on both.
- The hole listing is printed only when a metric actually grew; the corpus mismatch
  has its own message and its own exit code.

## Rationale

The ratchet exists so that a coverage regression cannot be merged quietly. The corpus
block was the one number in that file with no reader, and it was the number most
likely to be wrong: three hand-copied values, updated by hand, next to a matrix whose
header is generated. Two of the three had already gone stale on `main` without a
single gate noticing.

Checking the block costs one recomputation per run and turns a decorative number
into a contract. The deliberate consequence is that the gate is **red on the base
commit** until the values are corrected — a visible failure instead of silent drift —
and this PR corrects them.

The optionality is deliberate in the other direction: the block is a claim about the
corpus, and a floor file that does not make that claim is not a broken floor file.
But a claim that is present and cannot be compared is broken, so it fails loudly.

## Consequences

- The corpus block becomes a contract, not metadata. A maintainer who changes a
  scenario, a carrier or a spec must update the block in the same change; the gate
  says so instead of letting the number rot.
- The gate is red on any tree whose block has drifted — including `main` before this
  PR merges. That is the point: the drift was invisible until something read the
  block.
- `--accept-growth` is narrower than it looks. It excuses growth, not a stale claim.
- The denominator has one definition. A future change to what counts as an obligation
  changes the matrix and the gate together, by construction.

## Links

- `scripts/check-traceability-ratchet.py` — the gate.
- `scripts/src/traceability/coverage.py` — `Coverage.obligation_cells()`.
- `scripts/src/traceability/render.py` — the matrix header, the other consumer.
- `scripts/tests/test_check_traceability_ratchet.py` — the unit tests.
- `config/docs/traceability-ratchet.json` — the floor file and the block.
- gazon1/sing#3115, PR #3131.
