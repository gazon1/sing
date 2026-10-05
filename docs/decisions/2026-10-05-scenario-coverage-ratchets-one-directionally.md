---
title: Scenario coverage ratchets, one-directionally; growth needs a stated reason
date: 2026-10-05
status: accepted
tags: [kiwi, tcm, testing, infra, coverage, traceability, gates]
---

## Context

The scenario layer reached 19 specs, 34 claimed cells and 3 carriers, with **31
holes** — and nothing in the repository compared that number to anything.
`traceability validate` prints holes as information (`○ дыр в покрытии: 31 (это не
ошибка — это и есть смысл матрицы)`) and CI runs that same command as a step whose
exit code it treats as a pass. The two facts are both true and neither constrains
the other.

That is not hypothetical. The 15-spec auth/sync tranche landed with zero carriers
and took the matrix from 2 holes to 32 in one commit. The diff was large, the
`coverage-matrix.md` regeneration was correct, `validate` reported the new holes in
plain text, and the branch was green. **A number that is printed but not compared is
a number nobody reads**, and this repository already has a gate for exactly that
failure mode in a different layer.

The obvious fix — "every scenario must have a carrier" — was rejected in planning
and is still wrong: a scenario without automation is a legitimate state, and 16 of
the 18 live scenarios are in it today. A rule demanding otherwise would need an
exemption for the Android tier alone, which is unverifiable on this host.

## Idea

Repeat the shape that already exists next door. `check-baseline-ratchet.py` and
`config/coverage-ratchet.json` implement one contract: **the recorded number may
fall silently, rising it fails and must be justified in review.** Nothing about
coverage holes is special enough to need a different mechanism, and a second
mechanism would be a second thing to maintain and a second thing to get wrong.

## Decision

`scripts/check-traceability-ratchet.py` + `config/docs/traceability-ratchet.json`,
wired into **both** `check.sh` (step 8d) and `.github/workflows/ci.yml`, registered
in `SCRIPT_GATES` with a sabotage control, and pinned by
`scripts/tests/test_traceability_ratchet.py`.

Two metrics, and the second one survived an attempt to justify it.

`holes` is the count that was asked for. It sums over **(scenario, target) cells**
and cannot see a change that leaves the cell count alone while the number of
scenarios in trouble goes up. A brute-force search over every per-scenario
`(claimed, automated)` state on both targets found 85 such trades. The simplest is
a **spec split**: one scenario claiming `[android, desktop]` with no carrier is 2
holes and 1 dark scenario; split it per platform into two specs, each claiming one
target, and give neither a carrier — still 2 holes, now 2 dark scenarios. Nothing
was added to the suite and one unverified user scenario quietly became two.
Splitting a spec per platform is exactly the change that reads as tidying up.

`dark_scenarios` counts rows instead of cells, and moves on that trade.

The escape hatch is `--accept-growth`, off by default. It exists for the commit
that knowingly opens holes. It is **forbidden in a registered invocation** —
`check-gate-wiring.py` grew a `FORBIDDEN_IN_REGISTERED_INVOCATIONS` list for it,
because an escape hatch that becomes the default is not an escape hatch, and the
only trace would be a word in a command line no reviewer reads as a policy change.
That is the `--warn-only` class one level up.

## Rationale

The gate is derived from the same package that renders the matrix, by import
rather than by subprocess. A subprocess that failed to import would be
indistinguishable from a clean run, and the numbers being compared would be
produced by a different code path than the file that displays them.

The sabotage control mutates a **spec**, not the floor file, and the floor is not
the mutation target deliberately: raising the floor is a legitimate edit that must
not read as a regression, so the control has to make the corpus worse instead —
`targets: [desktop]` → `targets: [desktop, android]` on a scenario with no carrier
for it. The `assert` on the regex is not decoration; the same registry already had
a control that silently became a no-op when its floor line changed shape, and a
control that quietly stops sabotaging is reported as a passing control.

`_metrics` returned the wrong number on its first run (16 became 1) because the
deprecated filter was inverted, and nothing about that was loud — the gate printed
a number and passed. That is why the tests assert against the **real corpus** and
not only against a fixture, and why one of them is a plain inversion test.

## Consequences

Growth now fails in CI, which is the point. The floor file carries a `known_gaps`
entry saying the 16 Android cells are holes and will stay holes on a host with no
device — the gate does not pretend that tier is verified.

What the gate does **not** catch, stated rather than implied: deleting a scenario
spec lowers both metrics. A commit that deletes specs and adds a carrier passes
here while losing coverage. The spec set is the reviewable artefact and `validate`
still fails on a spec claiming two carriers or a link used twice, so deletion stays
visible and bounded. Deliberately not added: a floor on the number of specs, which
would make deleting a wrong spec a gate failure — and a gate that punishes
corrections is a gate people route around.

All 16 remaining dark scenarios are reachable work, and the cheapest next carrier
is now written down: probe reachability first, then write the test. See
`singularity-todo-kiwi-tcm-stand`.

## Links

- `config/docs/traceability-ratchet.json` — the floors and the notes
- `scripts/check-traceability-ratchet.py` — the gate
- `scripts/tests/test_traceability_ratchet.py` — including the spec-split fixture
- `2026-10-05-scenario-layer-replaces-per-class-reporting.md` — the layer this measures
- `2026-10-04-measurement-integrity` — the same shape applied to test runs
