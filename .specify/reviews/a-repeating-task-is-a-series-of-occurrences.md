---
change: a-repeating-task-is-a-series-of-occurrences
verdict: pass
date: 2026-10-25
pr: https://github.com/gazon1/sing/pull/1167
reviewed: cb59a198563e79d9bc9ea79f342f8f15e2e4747d
documents: sha256:fca411a5f28990448c1d36d8f196ec6560494a7a5f9c0cfdc5649ae9dd1ff373
---

## What was reviewed

The design of a repeating task as a **series of dated occurrence rows**, and the two
row badges (repeat, project). The artefacts:

- `specs/015-recurring-series-badges/spec.md` — 50 functional requirements
- `specs/015-recurring-series-badges/research.md` — fifteen decisions, D1…D15
- `specs/015-recurring-series-badges/plan.md`, `data-model.md`, `quickstart.md`
- `specs/015-recurring-series-badges/contracts/recurring-series-window.md`, `row-badges.md`
- `specs/015-recurring-series-badges/checklists/requirements.md`
- `docs/decisions/2026-10-25-a-recurring-series-carries-the-rule-on-every-row.md`
- `openspec/changes/a-repeating-task-is-a-series-of-occurrences/` — proposal, design,
  tasks, and the spec deltas for **two** capabilities: `tasks` (the occurrences) and
  `task-series` (the model of the series)

The review was a second agent's, given the artefacts, the code they cite and the
decision record — not the session that wrote them. It ran in **seven rounds**, and each
round's findings were closed before the next one began.

`reviewed:` is the commit the review was made against — the tree the reviewers read.
The contract has not moved since; the digest below is the one the gate recomputes.

## What was found, and what was done about it

**Rounds 1–4 — one pass and three failures, all closed.** The findings were about the
factual base: a claim about "members first" ordering that the code does not support
(corrected in five artefacts), six requirements without a scenario, a repair pass that
reopened rows it should not have, and a ceiling that refused where the design said it
rotates. Every one of them was closed, and round 4 returned `pass`.

**Round 5 — `fail`.** The branch had been rebased onto an `origin/main` that had just
gained the capability `openspec/specs/task-series/spec.md` — the owner of the series
model — and three of its requirements contradict this design literally.

1. **The ceiling rotation contradicted the detach rule.** The rotation clears the
   series id of the oldest closed occurrence; under the rule added for the user's own
   detach (a leaving occurrence loses the rule) that row would keep a rule with no
   series — exactly the shape the launch repair adopts into a *new* series, so the
   ceiling would have become a loop. Fixed: the rotated occurrence loses the rule too,
   stated in the spec, the research, the ADR, both deltas, the quickstart and the
   checklist.
2. **The upstream join was uncovered.** "Two series can be joined" takes the target's id
   and shared fields onto the other series' rows — which under the two kinds could mix
   a rule with hand-picked dates, or give the members different rules. Fixed with a
   MODIFIED requirement that refuses a mixed pair and names the reason, a join of two
   series of the same kind staying unchanged, and FR-050.
3. **The claim on the wire was unstated.** Fixed: a detached occurrence carries no
   rule, so the document carries `seriesId: null` together with `recurrence: null` — two
   claims that travel together, and the receiving device clears both.

Plus three minor ones: stale counts (48 requirements, thirteen decisions), a false
claim that the superseded ADR file "is not touched" (its frontmatter does change), and
the unexplained retention of an upstream scenario heading.

**Round 6 — `fail`.** The five findings above were confirmed closed, and the reviewers
found the same defect class one path over: when the occurrence that leaves a series was
the second of two, the row that stays behind also stands alone — and it kept the rule
(`DetachTaskFromSeries.kt:52-54`). Fixed: the leftover row loses the rule as well, in
the spec, the research, the ADR, the delta (text plus a new scenario), the quickstart,
the data model, the proposal, the design and the checklist. A minor finding — the spec's
change log had no row for the join — was fixed too.

**Round 7 — `pass`.** Both findings confirmed closed, the wording checked against the
repair, the rotation and the picked-dates kind (where the rule is a no-op), every cited
`file:line` re-read in the code, and the counts checked: 50 requirements, fifteen
decisions, two capability deltas. Three minor items it raised were closed afterwards:
the checklist named no assertion for the leftover row, the plan's summary was one
clause short, and FR-045 did not say that a closed occurrence may leave the series at
the user's request.

## What the verdict does not cover

The implementation. This verdict is about the design — the specification, the research,
the plan, the contracts and the two capability deltas — and about whether the claims
those documents make about the code are true. The code that implements them is not
written yet, and the tests named in the quickstart do not exist yet.

One thing outside this change is red: the `review verdicts cover every change` gate
fails on `origin/main` itself, because two changes from commit `6dfc690c`
(`observe-the-provider-session`, `replace-auth-with-kmpauth`) carry no verdict and are
not in the baseline. That failure is inherited, it is in main's own CI run
(`40212461686`), and this branch does not add to it.

## Verdict

**pass.** The design is implementable as written, its claims about the code check out,
its two capability deltas cover the upstream requirements they touch without leaving a
contradiction behind, and the seven rounds of review closed every finding that was
raised.
