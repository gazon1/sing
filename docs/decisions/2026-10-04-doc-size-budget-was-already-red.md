---
title: "check-doc-sizes was failing before anyone ran it"
date: 2026-10-04
status: accepted
tags: [ci, docs, tooling, process]
---

# check-doc-sizes was failing before anyone ran it

## Context

While adding four lines of `just gate` to `AGENTS.md`, `just docs-audit`
reported a failure that had nothing to do with those four lines:

```
Doc size budgets exceeded:
  AGENTS.md: 257 lines (max 250)
  docs/decisions/DIGEST.md: 1255 lines (max 1250)
```

`AGENTS.md` was **253 lines at HEAD** — already three over, before this branch
touched it. `DIGEST.md` was 1255, also over. Both budgets had been exceeded by
earlier work, and `python3 scripts/check-doc-sizes.py` exits 1, so the
`Check doc sizes` step in `.github/workflows/ci.yml` was red.

The two files that had drifted are both generated-or-curated indexes that grow
with every ADR and every skills-list edit. The budgets are a fixed number, and
the growth is automatic. Nothing warns at the moment of the change; the budget
only notices at the next run.

## Idea

- **A.** Raise both budgets. The files are "just" indexes.
- **B.** Trim the files back under the limit.
- **C.** Make the *generator* respect a per-source cap, so the pressure is
  absorbed where the growth happens.

## Decision

**C for the digest, B for `AGENTS.md`.**

The digest is generated, so the fix belongs in
`scripts/refresh-decisions-digest.py`. Two caps, because one was not enough:

- `MAX_BULLETS_PER_ADR = 6` — how many bullets a single ADR may contribute to a
  per-tag section. The existing `MAX_ITEMS_PER_TAG` bounds a *tag*, but one
  verbose ADR tagged `always` can fill every slot in every tag section at once.
  This is item 1 of the `digest-line-limit-pressure` recipe, open since
  2026-09-30.
- `MAX_ITEMS_PER_TAG` lowered **10 → 8**. The first cap alone brought the digest
  to exactly 1250 — zero headroom — and the three ADRs written later in the same
  session pushed it to 1257 again. Every section is already an index pointing one
  link away; 32 tags x 2 lines is 64 lines no reader scrolls to. Result: **1204
  lines, ~46 of headroom.**

The omission count was fixed alongside. The section footer said
`_... and N more items_` where `N = total - MAX_ITEMS_PER_TAG`; with a second cap
in play that formula no longer described what was on screen, and an index that
miscounts its own omissions is worse than one that overflows. It now reports
`total - shown`.

`AGENTS.md` is hand-maintained, so no generator exists to fix. It was trimmed
by ~6 lines: the retired-skills list lost its per-skill ADR filenames (the
decision files are the record; the AGENTS entry is a pointer), and the
`unwired-surface-audit` note lost its skill name. Both remain reachable. Now 244
lines against a 250 limit.

`MAX_DIGEST_LINES` stays at 1250. The backlog recipe lists raising it as option
2, explicitly behind this one, and a generator that can be trimmed at the source
is worth more than a larger ceiling.

## Rationale

The finding is worth more than the fix. A blocking CI gate was red, and the
reason nobody noticed is structural: `check.sh` — the thing everyone runs
locally — does not include it, so the gate only ever fires on a push, and the
push that broke it was a while ago.

That is the argument for `just gate` (`2026-10-04-one-gate-recipe.md`) covering
`docs-audit`, and it is why this ADR's consequence below says so. The two
decisions are one decision: the gate exists, it was red, and nothing local
looked at it.

Raising the budget would have been faster and would have moved the problem to
the next ADR. The limit exists to keep the digest an index; the moment it stops
being one, the limit has no meaning left to protect.

## Consequences

- `refresh-decisions-digest.sh` no longer prints a budget warning: 1204 lines
  against a 1250 limit. The caps bound growth rate, not the total, so the next
  ADR or two will test the headroom again.
- `AGENTS.md` is at 244 lines against a 250 limit, restoring a little headroom.
- `docs-audit` and `find-unwired-surfaces` should be added to `just gate`. They
  are fast, they are in CI, and this episode is the argument for putting them
  where a local run will see them. Not done here: `gate` shipped in this branch
  and the addition belongs with a run that proves the recipe end to end.
- A budget that is exceeded by automatic growth needs a check at generation time,
  not only at gate time. `refresh-decisions-digest.sh` warns on its own output
  but exits 0; making it exit non-zero would mean a doc rebuild can fail a
  build, which is a different trade and was not taken unilaterally.

## Links

- `scripts/refresh-decisions-digest.py` — `MAX_BULLETS_PER_ADR`, omission count
- `scripts/check-doc-sizes.py` — `AGENTS_MAX`, `DIGEST_MAX`
- `docs/decisions/deferred-backlog.md` — `digest-line-limit-pressure`
- `docs/decisions/2026-10-04-one-gate-recipe.md`
