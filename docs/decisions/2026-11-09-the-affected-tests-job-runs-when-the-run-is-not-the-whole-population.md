---
title: "The affected tests job runs when the run is not the whole population"
date: 2026-11-09
status: accepted
tags: ["ci", "tests"]
---

# The affected tests job runs when the run is not the whole population

## Context

Two jobs run tests on a pull request. The `quick` job ("Affected tests")
computes which test classes the change touches and runs exactly those, in
the fast population. The `tests` job ("JVM tests") runs the population the
`changes` job decided — `fast` on an ordinary pull request, `full` on one
labelled `run-full-tests`.

On an ordinary pull request the two jobs select different halves on
purpose: the quick job is the fast signal, the suite is the verdict. The
population contract (R5) says so, and the quick job says so in its own
comments.

On a `run-full-tests` pull request, the suite job runs the whole
population — every module the map can select, at every tag. The quick job
then computes a subset of tests and runs them again. It is the same work
twice, and the second copy cost 371 s.

## Idea

When the run is the whole population, a selection of part of it is not a
second answer. It is the same answer, later and slower.

## Decision

The quick job runs when the run's population is not `full`:

    if: ${{ github.event_name == 'pull_request' && needs.changes.outputs.population != 'full' }}

The population is read from the job that decided it, not from a label
spelled here. One answer to "which half runs" is what keeps the two jobs
from drifting apart.

Nothing about the selection changes. The map still answers "what is worth
running first", and on a fast run it is still the first thing that runs.

## Rationale

**A subset of a run that already happened is not a signal.** The suite
job's `full` population includes every module the map can select —
`:shared:jvmTest`, `:shared:testAndroidHostTest`, `:desktopApp:test`,
`:mcp-server:test`, `:core-domain:jvmTest` — at every tag. The map's
`fast` selection is a subset of that set. The map names
`:core-database:jvmTest`, but that module has no `src/commonTest`, so the
map never selects it.

**The suite job is the one that decides.** The population is chosen in the
`changes` job and published; the quick job reads the published name. A
condition that spelled `run-full-tests` itself would be a second answer to
the same question, and the label is read from two places already (the
`labeled` event and the pull request's label list).

**The job keeps its contract.** On a fast run the quick job is still the
first signal, and it still runs before the suite finishes its own work. The
change removes a redundant copy, not the signal.

## Consequences

- A `run-full-tests` pull request saves 371 s of runner time: the selection
  was a subset of the suite's own run.
- An ordinary pull request is unchanged — the quick job still answers
  "what is worth running first" in the fast population.
- The `ci-gate` job accepts a skipped job, so a skipped quick job does not
  fail the run.
- The population contract (R5) is untouched: the quick job still selects
  the fast half when it runs.

## Links

- `.github/workflows/ci.yml` — the `quick` job's condition
- `.github/workflows/ci.yml` — the `population` output of the `changes` job
- `config/test-populations.properties` — the populations the names select
- `docs/decisions/2026-10-22-ci-fast-only-pr-full-on-schedule.md` — the change that
  defined the two-population contract
