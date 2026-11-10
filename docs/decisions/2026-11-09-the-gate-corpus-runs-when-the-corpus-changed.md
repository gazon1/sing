---
title: "The gate corpus runs when the corpus changed"
date: 2026-11-09
status: accepted
tags: ["ci", "gates"]
---

# The gate corpus runs when the corpus changed

## Context

The `meta` job ("Gate tooling") runs the gate scripts' own unit tests. It
answered one question: does the corpus of the gates still work?

Its condition asked for three things: the `scripts` path filter, the `all`
path filter, the trunk, or a manual run. The `all` filter is `**` — every
path — so it was true on every pull request, and the job ran on every pull
request, including the ones that changed no gate script at all. It cost
303 s of runner time and paid for a corpus nothing had touched.

The filter it needed already existed. The `changes` job publishes
`needs.changes.outputs.gates`, which is exactly the corpus: `scripts/**`,
`config/**`, `.github/**`, `.githooks/**`. The `scripts` filter is wider —
it also covers `docs/**` — and it additionally decides whether the `static`
job runs at all, so widening it would change a gate's trigger.

## Idea

Ask the question that is already answered.

## Decision

The condition becomes `gates == 'true' || main || workflow_dispatch`. The
trunk and the manual run stay: the trunk is where the corpus lands, and a
manual run is a request to see everything.

The filter is not redefined. `gates` is already published, already means
"the corpus of the gates", and is already read by the control phase. One
question, one answer, two readers.

## Rationale

**The condition was already true everywhere.** `all` is `**`. A condition
that names a filter which matches every path is not a condition; it is a
constant dressed as one, and it made the job look conditional while it was
unconditional.

**The corpus is the corpus.** `gates` names the paths that make up the
gates: the scripts, the configuration they read, the workflow that runs
them and the hooks that run them. A pull request that changes none of them
cannot have broken the corpus's own tests.

**Reuse beats redefinition.** The alternative — narrowing the `scripts`
filter or adding a new one — would have produced a second answer to "which
paths are the corpus", and two answers drift. The corpus has one filter,
published by the job that has nothing else to do.

**The trunk keeps the proof.** The corpus's tests run on every push to the
trunk, so a broken corpus cannot reach the trunk.

## Consequences

- A pull request that changes no gate script saves 303 s of runner time.
- A pull request that changes a gate script, the gate configuration, the
  workflow or the hooks runs the corpus, as before.
- The trunk and manual runs are unchanged.
- The corpus is still proven on the trunk, so a broken corpus cannot land.

## Links

- `.github/workflows/ci.yml` — the `meta` job's condition
- `.github/workflows/ci.yml` — the `gates` filter in the `changes` job
- `docs/decisions/2026-11-09-the-control-phase-proves-the-affected-gates.md` —
  the other reader of the same filter
