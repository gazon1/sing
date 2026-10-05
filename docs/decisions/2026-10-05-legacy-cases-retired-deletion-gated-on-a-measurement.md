---
title: The 259 legacy cases are retired; deletion waits on a measured precondition
date: 2026-10-05
status: accepted
tags: [kiwi, tcm, testing, coverage, traceability]
---

## Context

`2026-10-05-scenario-layer-replaces-per-class-reporting.md` decided that the
scenario layer replaces per-class reporting. It was explicit that it did **not**
authorise the migration: "no issue is filed for it here — a 259-case move needs
its own proposal."

That left the decision honest and the repository unchanged. Measured 2026-10-05,
after the auth/sync tranche landed:

| | count |
|---|---|
| specs in `infra/kiwi/scenarios/` | 19 |
| Kiwi carriers (linkage in code) | 3 |
| scenarios with any carrier | 2 |
| claimed cells automated | 3 of 34 |
| legacy `Automated/*` cases | 259 |
| legacy case `summary` | the test class name — `sync.py:148` returns `self.simple` |

The two layers are not equivalent, and one of them answers a question nobody is
asking. A legacy case is named after a file; a scenario names a behaviour and
carries preconditions, steps and an expected result. So the repository currently
has **two** answers to "what do we verify?" and the one that can answer it has
17 of 19 rows empty.

## Decision

**The 259 cases are retired. Deletion is gated on a measured precondition, and
that gate is this ADR's most load-bearing clause.**

1. **Demote first, delete last.** `gaps.py` now prints a `!! LEGACY` banner
   naming what it measures and pointing at `docs/testing/coverage-matrix.md`.
   Executed, reversible, and it is the step that actually removes the ambiguity:
   the misreading happened because the report looked authoritative, and
   documentation had not prevented it.

2. **The precondition for deletion is a number, not a date.** Deletion proceeds
   when the scenario layer can absorb them — concretely, when **no scenario is
   claimed-and-un-automated on a target that still has a live counterpart in
   `Automated/*`**. A date would delete the layer's only record of run history
   for 259 cases while 31 of 34 claimed cells are still holes, and run history
   is not in Git.

3. **Deletion is not authorised by this ADR**, and saying so is the point. The
   259 cases hold `TestExecution` history and an `ever_run` property that
   `prune.py` and `config/docs/kiwi-gaps-baseline.txt` both read. Scenario cases
   are safe to delete — `seed` rebuilds them from Git, which is why the pilot
   case could be removed and restored — and legacy cases are not, for exactly
   the reason the pilot case was.

## Rationale

**Why the precondition is a count.** The failure mode of retiring a layer is
deleting the only copy of something. The scenario layer's specs *are* the
canonical copy; the legacy cases' execution history is not, and never was. So the
migration is safe exactly when the thing being replaced has been replaced, and
"has been replaced" is measurable per target while it is not measurable in
calendar terms.

**Why demote rather than just decide.** A decision nobody meets in practice is a
decision nobody applies. The banner costs four lines and is the only part of this
ADR that changes what a person sees when they run the tool.

**Why the legacy layer keeps running until then.** `gaps.py` and its floor
(`config/docs/kiwi-gaps-baseline.txt`) are currently the only instrument that can
say "this class has never run". While it runs, that is a fact someone can act on.
Removing it before the scenario layer can say the same thing about behaviours
would leave a real question with no instrument at all.

## Consequences

- `just kgaps` is a transitional tool. It is not deprecated in the compiler sense
  — it is still the only per-class instrument, and its floor must not be lowered
  to make room for this decision.
- Every new scenario should arrive with a carrier in the same change, or the
  hole count rises and the precondition moves away. The auth/sync tranche shipped
  15 specs and 0 carriers, which is the specific shape this ADR is written
  against.
- Deleting the 259 cases is a separate, explicitly authorised change with its own
  verification. It is not a task this document completes.
- The Kiwi stand is a local, manually-seeded projection and is currently 18
  scenarios behind the specs. Nothing in CI notices that; `just kiwi-seed-check`
  does, and it is a local command. See the issue filed alongside.

## Links

- #157 — the decision this executes; the migration it deliberately deferred
- #156 — the scenario batch, whose acceptance criterion is a matrix a reader can
  act on (which is why the matrix now carries prose, not only glyphs)
- #151 — the Android tier, which is what blocks half the claimed cells
- `docs/decisions/2026-10-05-scenario-layer-replaces-per-class-reporting.md`
- `docs/testing/coverage-matrix.md` — the answer to "what do we verify?"
- `infra/kiwi/sync.py:148` — `summary` returning the class name
