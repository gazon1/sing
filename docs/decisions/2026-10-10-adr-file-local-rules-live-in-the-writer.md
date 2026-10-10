---
title: "File-local ADR rules live in the writer, not in the gate"
date: 2026-10-10
status: accepted
issue: "#509"
---

**Date:** 2026-10-10
**Status:** accepted
**Issue:** #509

## Context

Seven separate scripts checked properties of the ADR corpus, and between them they
enforced 37 rules. Four of those rules can only be decided by someone holding the
file in hand at the moment it is written:

- status must come from the vocabulary in `config/docs/adr-corpus.json`
- `status: superseded` requires a `supersededBy`, and it must name a file that exists
- `status: archived` is not a status a tool may write — it describes a *location*
  (`docs/decisions/archive/`), and a file can only get there by being moved
- a document with no parseable frontmatter is not edited by guesswork

A gate checks these over all 709 files after the fact. It is the right mechanism for
the other 33 — how old a file is, whether it still sits where it belongs, whether a
merge changed it underneath nobody. Those need history, and a writer has none.

## Options considered

1. **Leave all 37 in the gate.** Two implementations of the same rule, one in Python
   and one in Kotlin, free to disagree. This already happened: `check_adr_status.py`
   accepted a vocabulary of five spellings because it had never seen the files
   `deferred/` added, while the writer had.
2. **Move all 37 into the writer.** A writer cannot see a merge, a hand edit, or a
   deletion. The rules that need history would simply stop being enforced.
3. **Split by what the writer can know.** File-local rules in the writer as an API;
   historical rules stay in the gate.

## Decision

**Option 3.** A rule lives in the writer exactly when the answer is available at
write time, and in the gate exactly when it is not.

The gate keeps: age, location, drift from history.
The writer takes: vocabulary, `supersededBy` presence and resolvability, `archived`
refusal, frontmatter shape.

Concretely: `AdrStorage.updateAdr` is the single implementation of the four rules, and
it is exposed as an API — CLI (`#510`), MCP tool, and hand use all reach the same
method. A gate that wants to re-check a file-local rule reads the file; it does not
re-implement the rule.

## Rationale

**A tool is an API; a gate is a mechanism of enforcement.** The same rule reached
through two implementations is two rules with two futures. The vocabulary drift was
not a bug in either script — it was the expected consequence of writing the rule
twice and touching one of them.

**The gate is where you find out anyway.** The four rules moved into the writer do not
stop being checked for hand edits, merges, or bulk scripts. The gate still reads every
file. What changes is that the writer can no longer produce a violation *and* cannot
know it, so the gate's remaining job is finding violations the writer could not
prevent — which is a job it can do well.

**Bulk migrations stay scripts.** A writer validates one file it is about to write. A
migration touching 34 files is not a writer, and `normalize_status` deliberately does
not map out-of-vocabulary values: `CLOSED → accepted` is a one-time decision (#516)
that a runtime helper silently making on every read would turn into an unreviewable
policy.

## Consequences

- `updateAdr` writes frontmatter only. The body is never parsed back, so it cannot be
  rewritten by accident — and a test asserts the body survives byte for byte.
- The gate's rule count drops from 7 to 3 (#523). Four rules leave the gate because
  they leave the file's owner, not because they stopped mattering.
- An ADR whose status is changed by hand still gets caught. It just gets caught by
  the gate, which is where it should have been caught.

## Related

- `#509` — `AdrStorage.updateAdr`, the writer this decision describes.
- `#510` — CLI surface over the same method.
- `#516` — one-time status normalisation for `deferred/`; deliberately *not* a
  runtime mapping.
- `#517` — `supersedes` validation, the historical half of what the writer cannot see.
- `#523` — shrinking the gate to the three rules that belong to it.
- `#520` — the corpus rules both sides now read from `config/docs/adr-corpus.json`.