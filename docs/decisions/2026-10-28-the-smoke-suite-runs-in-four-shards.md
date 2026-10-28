---
title: "The smoke suite runs in four shards, and the number in the conventions is checked"
date: 2026-10-28
status: accepted
tags: ["e2e", "maestro", "ci", "tooling"]
---

## Context

The suite a pull request runs is selected by the `smoke` tag, and it ran on
**one** shard. The full suite runs on four. So the "minimal regression suite"
was a single runner doing 27 flows while the nightly was four runners doing
29 each — the pull-request path cost nearly a shard of the nightly, and it was
the path every pull request took.

The tag had spread for months without anyone noticing, because the tag is what
selects. `Maestro/CONVENTIONS.md` still said the suite was six flows and
named six paths, four of which did not exist; the tag selected 27. The
documented number was fiction and the flow that ran was not the flow the
document described — two separate facts, and the second one was correct.

Nothing checked either. `scripts/check-maestro-flow-catalogue.py` holds the
*list* of flows to the tree, and it says nothing about which of them the
pull-request path runs.

## Idea

- **A.** Narrow the tag back to the six flows the conventions name.
- **B.** Rename the suite and run the full suite in four shards on pull
  requests too.
- **C.** Keep the tagged suite, run it in four shards, and hold the number in
  the conventions to the tag.

## Decision

**C.**

`config/docs/e2e-shards.txt` says `smoke 4`. The shard is taken over every
flow and the tag is applied afterwards — the runner's own rule — so the four
shards hold the same 27 flows between them, in roughly 7/9/4/7. The
pull-request path is now well under half of a full-suite shard, and it finishes
in a third of the wall clock it used to take, on the same four runners the
nightly uses.

The conventions carry the size — `Current smoke flows (27):` — and
`scripts/check-maestro-smoke-suite.py` holds two things to it:

- **the count** — the number in the conventions against the number of flows
  that carry the tag;
- **the cost** — the largest smoke shard against the largest full-suite
  shard. A smoke suite that costs more per shard than the nightly is not a
  subset, and the gate says so before the tag can grow into one.

The list of flows is not written out in the conventions any more. A written-out
list is a second place for the suite to be wrong, and it was the wrong one;
`python3 scripts/check-maestro-smoke-suite.py --list` prints what the tag
selects.

**A is not on the table.** Narrowing the tag to six would drop 21 flows from
the pull-request path, and the tag grew *deliberately* — the commits that added
it say so ("the notes flows join the smoke suite", "close the android half of
the coverage matrix"). The six were never the intent; the prose was stale.

**B is not on the table.** It buys the same coverage at the same price as the
nightly and makes the two paths identical, which is a larger commitment than
the problem needs, and it would have removed the tag that says what a flow is
for.

## Rationale

- **The pull request is the common case.** It is the path that runs on every
  labelled pull request and on every push to `main`, and it was the slowest
  thing in the workflow.
- **Sharding is free here.** The runner already shards before it filters; a
  tag-selected suite needed a number in the plan file and nothing else. The
  four shards run exactly the flows the one shard ran.
- **A number nobody checks is a number nobody can plan against.** The gate
  exists so the next person who adds a smoke flow meets a red build that says
  "the conventions say 27, the tag selects 28" instead of a doc that is wrong
  for another season.
- **The cost invariant is the one that keeps the name honest.** The count can
  grow — it is a fact about the suite. The *cost* is a promise: a pull request
  is cheaper than the nightly. That one is worth a gate of its own, because it
  is what went wrong.

## Consequences

- **The pull-request path runs on four runners** instead of one, and finishes
  about three times sooner. The CI minutes are the same order as the nightly's
  and the wall clock is a third of what it was.
- **The conventions carry a number that moves.** Adding a flow with the `smoke`
  tag makes the gate red until the number is updated — in the same commit,
  which is where the flow is added.
- **The shard counts live in one file.** `config/docs/e2e-shards.txt` is read
  by the nightly's `plan` job and by `scripts/e2e-shard-plan.py`, so a local
  reproduction of a red shard runs the same flows the shard ran.
- **The failure floor is unaffected.** The `smoke` scope of
  `config/docs/maestro-failure-floor.txt` is measured over the whole tagged
  set, and the `maestro-results` job merges the shards' reports before it
  measures anything. The number allowed to fail did not change because the
  number of shards did.

## Links

- `config/docs/e2e-shards.txt` — the shard counts
- `scripts/check-maestro-smoke-suite.py` — the gate
- `scripts/src/emulator/maestro/_flow.py` — the runner's selection and sharding
- `Maestro/CONVENTIONS.md` — the number, and the rules the flows follow
- `scripts/e2e-shard-plan.py` — which flows a shard holds
- `2026-10-20-the-shard-plan-is-one-file.md` — why the plan is a file
- `2026-10-01-maestro-flow-tag-contract.md` — why the tag selects
- `2026-10-28-maestro-measurement-gate.md` — the failure floor is per scope and
  is measured over the whole suite, so the shard count does not move it
