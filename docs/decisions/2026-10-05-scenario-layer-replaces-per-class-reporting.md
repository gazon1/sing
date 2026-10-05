---
title: The scenario layer replaces per-class reporting; Automated/* retires; Kover keeps code coverage
date: 2026-10-05
status: accepted
tags: [kiwi, tcm, testing, infra, coverage, traceability]
---

## Context

`2026-10-05-scenario-test-cases-in-kiwi.md` made a Kiwi TestCase a **user scenario**
and left the 259 legacy cases in the `Automated/*` plans, unfrozen from
reclassification but frozen in practice. That left two artefacts answering the same
question — "what is untested" — with opposite polarities:

| | `gaps.py` / `just kgaps` | the coverage matrix |
|---|---|---|
| unit of answer | test class | user scenario |
| scope | 259 cases in `Automated/*` | the `Scenarios` plan |
| "never run" means | **a failure** — floored by `config/docs/kiwi-gaps-baseline.txt` | **normal** — the scenario may be new, or its target may be a separate CI job |
| the failure is | a class with no executions ever | a claimed scenario with no result **at a commit that ran** |

Both are correct about their own question. The reader who arrives cold has no way to
tell which answers theirs, and `infra/kiwi/README.md` plus the
`singularity-todo-kiwi-tcm-stand` skill explain the split — but documentation is not
a decision, and the two vocabularies were already drifting: "hole" means a claimed
target with no automation in one layer and something closer to "never run" in the
other.

The question this ADR answers is the one #157 filed: **does the scenario layer
eventually replace the legacy per-class reporting?** Until it is answered, the split
is a permanent tax and every future reader pays it.

## Decision

**Yes — the scenario layer replaces per-class reporting.** The `Automated/*` plans
retire; the scenario layer becomes the single answer to "what is untested, and what
was the last result". **Kover keeps code coverage**, because a scenario is not a
class and the per-class instrument it would replace was answering a different
question.

Concretely, the destination state is:

| Concern | Owned by | Not |
|---|---|---|
| is this user behaviour verified | scenario layer, per target | — |
| did it pass, and when | scenario layer, one Kiwi run per (commit, target) | — |
| which classes have never run | **Kover + the floor**, not Kiwi | Kiwi per-class executions |
| what fraction of code is exercised | Kover | scenario matrix |

## Rationale

**Why the legacy layer retires rather than coexists.** Two authorities for one
question is the defect; adding a third instrument does not fix it. The per-class
layer's own floor had to be invented to stop it reporting a regression in the
*number* of never-run classes — a floor exists precisely because "never run" is the
wrong polarity for a unit test that may legitimately not be worth running. That is
the same reasoning that makes a scenario's silence benign, so the two layers cannot
be merged into one report without one of the two polarities being lost. Retiring one
keeps the polarity that matches the question.

**Why Kover stays, and why it is not "the old layer renamed".** Kover measures
executed lines in `com.singularity.todo.*`, which is a statement about code and
cannot be expressed as a user behaviour at all. It has no per-class run history and
needs none. The replacement is therefore of the *run-history* question, not of code
coverage — and collapsing the two would be how the scenario layer ends up answering
questions it was never specified for.

**Why the decision is recorded before the layer grows.** With one scenario
(#156), the machinery costs more than it reports, and a second authority is cheap to
tolerate. It stops being cheap exactly when the second layer is useful — at which
point a reader has no way to tell them apart. Deciding at one scenario is cheap;
deciding at thirty is a migration under pressure.

## Consequences

- **Retiring `Automated/*` is a multi-session migration, not a single commit.** It
  moves 259 cases and their execution history. Until it happens the split is real,
  and this ADR is the sentence that says which layer wins when they disagree.
- **Vocabulary is fixed now**, so the migration is mechanical later: "hole" =
  claimed target with no automation; "not-run" = claimed, ran, no result; "never
  run" = the legacy per-class instrument, which stops being a first-class term as
  the layer retires.
- **`gaps.py` keeps running until the migration lands.** Its floor is not to be
  lowered to make room for this decision; it is a real regression detector for as
  long as both layers exist.
- **The migration must not be inferred from this ADR.** It is not authorised work
  and no issue is filed for it here — a 259-case move needs its own proposal, and
  the proposal needs the scenario layer to have enough scenarios to carry the
  replacement (#156).

## Links

- #157 — the question this answers
- #150 — why the Android column is empty in CI and what that does and does not mean
- #156 — enough scenarios for the layer to be worth more than it costs
- `docs/decisions/2026-10-05-scenario-test-cases-in-kiwi.md` — the layer itself,
  and decision 6 there (legacy cases frozen, not migrated) is what this one
  eventually supersedes
- `openspec/specs/test-execution-integrity/spec.md` — REQ-13, the per-scenario rule
  that the retired layer has no equivalent of
