---
title: "Six of seven CI jobs could hang for six hours"
date: 2026-10-06
tags: [ci, gates, architecture, testing]
status: accepted
---

## Context

While auditing why the GenUI work could not be verified from CI, two facts surfaced that have
nothing to do with GenUI:

1. **Every job in `.github/workflows/` runs on `ubuntu-24.04` and five of six jobs had no
   `timeout-minutes`.** GitHub's default is 360. A job that wedges on a Gradle daemon or a network
   fetch therefore holds a runner for six hours and reports the outcome as a pass with a timeout at
   the end — a distinction nobody reads in a badge.

2. **`check-gate-wiring.py` reported two gates as broken when they were correctly wired.**
   `scripts/check-publication-hygiene.py` and `scripts/check-readme-claims.py` run in `ci.yml` and
   not in `check.sh`, which is the intended arrangement — but neither appeared in the script's
   `GATE_PARITY` table, so the parity check printed
   `runs in ci, declared both — no reason declared`. The message is wrong twice over: "declared
   both" is not what the table says, and the gate was not broken.

The second one matters more than the first, because it is the shape this repository already has a
gate for, and the gate had stopped distinguishing it.

## Idea

1. **Fix the message.** Reword so a correct-but-undeclared asymmetry reads as undeclared rather
   than as broken.
2. **Fix the KDoc.** The table's doc comment described it as "where each gate is *expected* to run",
   which reads as a manifest — the opposite of what the code does with it.
3. **Declare the two gates.** Add them with the reason each is CI-only.
4. **Add `timeout-minutes` everywhere.** Bound each job from its measured duration.

## Decision

All four.

**`GATE_PARITY` is a table of deviations, not of expected placements.** A `check-*.py` gate is
expected to run in both `ci.yml` and `check.sh`, and needs no entry; an entry declares an asymmetry
and carries its reason. The KDoc now says exactly that, in the first sentence, because the previous
wording inverted it and led directly to reading the two healthy gates as defects.

**Both gates are declared CI-only, for a reason that is about facts rather than convenience.** Both
read committed state: publication hygiene asks what would reach main, and README claims compares
numbers against the tree *as merged*. A developer's working tree is neither, so running them
locally would measure a tree nobody else has. That is the same shape as the `--partial` incident
that created the table in the first place.

**Every job gets a `timeout-minutes`, sized from a measured local duration and rounded up.** The
bound is not about speed; it is about converting six hours of a held runner into forty minutes of a
red job.

## Rationale

Both findings are the same defect wearing different clothes: a check that reports the wrong thing
about a correct thing. The parity check had a table whose meaning was only discoverable by reading
its body rather than its documentation, and a diagnostic string that described neither the intent
nor the actual state. Nothing was broken in the sense of a wrong verdict — it did flag a real
undeclared asymmetry — but it flagged it with a sentence that would have sent the next reader to
fix a gate that was fine.

That is more expensive than an outright failure. A gate that says "broken" when something is broken
is doing its job. A gate that says "broken" when something is correct trains its reader to discount
it, and a reader who has learned to discount a message stops reading the ones that matter.

The same reasoning applies to the missing timeouts. A run that fails fast is a fact in the log. A
run that holds a runner for six hours and fails at the end is a fact nobody looks at, and it costs a
runner while producing it.

## Consequences

- All six `ci.yml` jobs and the one `docs-audit` job carry `timeout-minutes` (15–60).
- `GATE_PARITY` gains two entries with reasons; `check-gate-wiring.py` reports clean.
- Two script-test failures fixed at source rather than baselined: a README claiming `schema v38`
  against a tree at 39, and a test that asserted a hardcoded `38` where the property under test was
  "the reader follows `SCHEMA_VERSION`" — the second being the mechanism by which the first went
  unnoticed.
- The Kiwi inventory bound (320 → 330) raised after verifying all 321 scanned classes are real test
  classes, which is what the bound exists to distinguish from returned helpers.

## Superseded in part

Main landed #217 while this was being written, which consolidated the seven workflows into four
jobs and bounded every one of them with `timeout-minutes`. The `docs-audit.yml` file was deleted and
its openspec validation moved into `ci.yml` against a pinned CLI version.

So the first finding is now **fixed by someone else, more thoroughly** — the workflows here were
dropped rather than re-applied over a structure that no longer exists. The second finding, the
`GATE_PARITY` KDoc and its two undeclared entries, was not touched by #217 and is fixed here.

The ADR is kept rather than deleted for one reason: it is now the record of *why* #217's structure
is shaped the way it is, and why the `timeout-minutes` on those jobs are load-bearing rather than
decoration. A workflow change that drops them would otherwise look like cleanup.

## Links

- Code: `scripts/check-gate-wiring.py`, `.github/workflows/ci.yml`, `.github/workflows/docs-audit.yml`
- Tests: `scripts/tests/test_check_readme_claims.py`, `scripts/tests/test_kiwi_sync.py`
- Related: `docs/decisions/2026-10-06-an-instrument-nobody-reads-is-not-an-instrument.md`
- Related: ADR `2026-10-05-koin-w003-in-a-test-graph.md` (the `--partial` incident)