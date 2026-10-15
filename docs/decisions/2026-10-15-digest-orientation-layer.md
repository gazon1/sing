---
title: The digest split into an always-loaded layer and an on-demand index
date: 2026-10-15
status: accepted
tags: [docs, tooling, process, ci]
---

# The digest split into an always-loaded layer and an on-demand index

## Context

`docs/decisions/DIGEST.md` was two things wearing one file. It was the document
`AGENTS.md` told every agent to read before a non-trivial task — so it was paid for on
every task — and it was simultaneously the complete index of the decision corpus. The
consequence showed up as a repeating incident: a new ADR pushed the digest past its
1250-line budget, and the generator was trimmed to compensate.

Four rounds of trimming are visible in `refresh-decisions-digest.py`:

- `MAX_BULLETS_PER_ADR` 6 → 3 → 2 (last on 2026-10-13, the day the digest hit 1256/1250)
- `MAX_ITEMS_PER_TAG` 10 → 9 → 8
- a duplicate `slug -> tags` index of ~385 lines dropped (2026-10-03)
- `MAX_ACTIVE_ENTRIES` capped

`2026-10-04-doc-size-budget-was-already-red` recorded the diagnosis at the time and
chose option C — trim the generator rather than raise the ceiling. That was right as far
as it went, and it did not fix the cause: the ceiling was the wrong number for the job
the file was doing. The trimming could only ever postpone the next incident, because the
growth was not incidental. One ADR means at least one more line, forever, in a file
every session reads.

Meanwhile the orientation question had no answer at all. `AGENTS.md` said "read the
digest", which is 1150 lines of per-tag bullet extraction — a view built for someone
already looking for a specific decision, offered to an agent that only needs to know what
exists and where. Nothing in the repository listed the documents themselves. `tree docs`
answers that in 779 lines, of which 598 are ADR filenames: it answers a different
question just as badly.

The measured size was not known while planning this, because measuring meant running the
generator, which writes. The last recorded figure in a comment was 1159.

## Idea

- **A.** Raise the budget again and keep trimming.
- **B.** Keep one file, reduce it to a curated "most important decisions" list.
- **C.** Split by **kind**, not by size: an always-loaded orientation layer that cannot
  grow with the corpus, and the complete index beside it, opened on demand.
- **D.** Delete the digest and tell agents to run `tree docs`.

## Decision

**C.**

`DIGEST.md` becomes the orientation layer: where to look, a generated tree of `docs/`,
the N most recent decisions, and a wrapped line of decision tags. `DIGEST-full.md`
becomes the complete index — the old body, with the caps relaxed because the reader who
opens it opened it deliberately.

The layer is bounded by **kind**, not by corpus size. Nothing in it is one-per-ADR: the
recent list is a fixed 20, the tags are wrapped, and the tree's depth is fixed. A new ADR
therefore cannot push it past `MAX_ORIENTATION_LINES = 120`. That is the property the
single file could never have, and it is why the four trimming rounds are ended rather than
continued — the caps now on `DIGEST-full.md` (20 per tag, 6 per ADR, 600 active entries)
exist to stop one verbose ADR dominating a tag section, not to buy headroom against growth.

Two consequences worth stating:

The orientation layer and the doc tree are **one artifact rendered by one generator**. They
were two components in the plan that produced this. Keeping them separate would have
created exactly the duplication being removed: two generated surfaces both answering
"what durable knowledge exists". `scripts/print-source-tree.py` — which renders the
*code* tree into `ARCHITECTURE.md` — is the pattern this follows, not a thing to
reinvent beside it.

Drawing the tree lives in `scripts/docs_tree.py`, called by the generator. That module
takes every ADR count as an argument and calls `adr_corpus.load()` nowhere, so it is not
a registered corpus consumer — which is also why `check-adr-config-sync.py`, which fails
any directory walk inside a corpus consumer, does not object to it. Drawing a directory
tree and deciding what an ADR is are different jobs; putting the second one in the first
is what #520 was about. The temptation was to name a variable until the gate's regex
stopped matching, which would have taught the next reader that the gate can be talked out
of anything.

`DIGEST-full.md` is gitignored alongside `DIGEST.md` and added to
`config/docs/adr-corpus.json` `ignoredFiles`. Without the second entry
`check-doc-dead-refs.py` would report the new file as a dead reference the first time
anything linked to it.

### The digest budget was not being enforced

While splitting, the gate that was supposed to catch an over-budget digest turned out not
to. `sgate decisions_digest_within_budget` step 1 looked for

    DIGEST.md is N lines (budget: M)

and the generator printed

    WARNING: DIGEST is N lines (limit: M)

— a different phrase and a different word. The regex matched nothing, `passed` was
`exit_code == 0`, and the script always exits 0. So the step had never failed on anything,
and the only thing enforcing the budget was `check-doc-sizes.py`, a step away. The
generator now prints one line naming both digests in the shape the gate parses, and exits
non-zero when either is over.

This is the shape of `2026-10-06-a-gate-that-lies-is-worse-than-no-gate` wearing a
different hat: not a gate that lies, but one that had nothing to say.

**D was rejected** on measurement, not taste. `tree docs` is 779 lines and `tree docs -L 2`
is 598, because 590 ADRs sit flat in `docs/decisions/`. No depth limit fixes a flat
directory, and the useful part of the idea — that names carry their own meaning — is
already true here and is what this decision leans on instead.

## Rationale

A budget the growth always reaches is a wall, not a ratchet. The trimming worked, and cost
four rounds of it to buy a few months each time, because it treated the symptom. Splitting
the file by role removes the pressure rather than the pressure's outlet.

The orientation layer is also the thing the repository was missing. Three hand-maintained
or generated indexes were answering "what exists" for the same reader:
`AGENTS.md`'s hand-picked list of key skills, `SKILLS-CATALOG.md`, and the digest's
per-tag sections. Each needed its own upkeep, and they drifted from each other — which is
why the retired-skill list in `AGENTS.md` was already stale before this work started.

## Consequences

- The always-loaded layer drops from ~1159 lines to 79 measured on 2026-10-15. Token cost
  per session falls by an amount not yet measured — the eval in `evals/` is the honest way
  to claim that, and it has not been run.
- `MAX_ORIENTATION_LINES = 120` is a ceiling the corpus cannot reach by growing. The test
  `test_the_orientation_layer_has_room_for_its_own_sections` asserts the fixed sections fit
  inside it.
- The budget number is now declared in two files (`refresh-decisions-digest.py` and
  `check-doc-sizes.py`) for the first time. `BudgetIsDeclaredOnceTest` asserts they agree,
  because that duplication is the #520 shape.
- `AGENTS.md` says "read the digest" in two places. One was removed; the other now names
  the layer and points at the full index. `AGENTS.md` went from 238 to 239 lines against a
  240 ceiling, so the retired-skill list moved to `docs/doc-maintenance.md` to pay for it.
  One line of headroom is thin; the number was not raised because raising it is the option
  `2026-10-04-doc-size-budget-was-already-red` already rejected.
- An agent asking "what did we decide about X" now has two paths instead of one: the recent
  list and the tag line for a quick answer, `grep` over `docs/decisions/` for an exhaustive
  one. The filenames are date-prefixed and self-describing, so `grep` is genuinely cheap —
  this is the assumption the tree-docs argument rests on and it is worth testing.

## Links

- `docs/doc-maintenance.md` — writing rules, including what is deliberately not a rule here
- `2026-10-04-doc-size-budget-was-already-red` — the diagnosis that started this
- `2026-10-06-a-gate-that-lies-is-worse-than-no-gate` — why the dead regex mattered
- `2026-10-15-reference-skills-become-documents` — the other half of the same change