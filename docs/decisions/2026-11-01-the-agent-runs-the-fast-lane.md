---
date: 2026-11-01
status: accepted
deciders: [maintainer]
---

# An agent runs the fast lane; the full loop stays the loop

## Context

The local loop (`./check.sh`) costs about fifteen minutes, and the numbers are
published (`docs/testing/gate-costs.md`): the whole JVM suite is 6m44s, the
three steps that test the gate machinery itself are 5m, and the module-wide
detekt sweep costs minutes more in a fresh worktree, because the Gradle
configuration cache is cold there.

An agent that works one issue at a time pays that price per iteration. The
runner (`pi-afk-runner`) opens a fresh worktree per issue, so the price is the
cold one, and the worktree's Gradle build competes for the three machine-wide
build slots — every minute of the loop is a minute another issue cannot have.
The cost is not the suite running: it is the suite running *about things the
change did not touch*.

The full loop is not wrong. It is CI's loop, and it is the loop a human runs
before merging. The question was only which loop an unattended agent runs per
issue.

## Idea

Two loops, named, and the cheap one is the one that runs per change:

- **the fast lane** — the checks that can catch THIS change: the app compiles,
  the test classes the change can break run, the detekt of the files it
  touched runs, and the tree-level static gates run;
- **the full loop** — everything, and it is what CI runs and what a merge runs.

The tools for the fast lane already exist: `scripts/test-impact-map.py` (the
selection CI's "Affected tests" job asks), `scripts/lint-changed.py` (the
scoped lint the pre-push verdict uses) and `sgate run-all` (the static
registry). Nothing new had to be written to answer "what does this change
require?" — the answer was already computed, three times, and the loop simply
did not ask it.

## Decision

`./check.sh --fast` is the lane. It runs the preflight, the app compile, the
affected test classes, the scoped lint and the static gates, and it prints
what it did not run.

The full loop is unchanged and stays the default of `./check.sh`. The agent
(the repository's own review skill, and the runner's contract, prompt and gate
list) names the fast lane.

The fast lane is a LANE, not a smaller gate: what it skips is judged by CI,
and by the merge pipeline, which runs the full loop again on a branch that
moved. Nothing is judged nowhere.

## Rationale

The lane answers the question the change actually raises — "does this break
something?" — and the full loop answers a question about the whole tree,
which is CI's job and is paid for once per push instead of once per iteration.

The cost record was the deciding fact. Cutting the suite and the
gate-machinery steps removes about eleven of the fifteen minutes; the
remaining four are the compile, the tests, the lint and the static gates, and
they are the part that is about this change.

Measured on 2026-11-01 in this worktree, the fast lane took **5m09s**: the
compile 34s, the affected test classes 2m06s, the scoped lint 0.1s (no changed
Kotlin file), and the static gates 47s. The full loop on the same tree is
longer than its published baseline, because a fresh worktree has a cold Gradle
configuration cache and the module-wide detekt sweep pays for it — 7m26s for
`:shared` alone, measured 2026-10-23 and published in
`docs/testing/gate-costs.md`.

The fast lane was made a flag and not the default because the full loop is
what the repository's ADRs mean by "the local loop", and several gates read
that phrase as "the loop CI mirrors". Changing the default would have changed
the meaning of that phrase in every document at once. A flag says the same
thing without the rewrite: this loop, for this job.

## Consequences

- The repository's review skill, its AFK contract and the runner's gate list
  name `./check.sh --fast`.
- The fast lane writes its timings to `build/diagnostics/fast-lane-steps.txt`,
  not to the full loop's record: `sgate gate-costs` publishes the latter, and a
  fast run must not overwrite the measurement of the loop it is not.
- `--affected` and `--fast` share one implementation of the affected selection
  (`run_affected_tests`), so the two lanes cannot drift apart.
- The lane is weak in a known way: it does not run the architecture gates, the
  ADR suites or the instrumented tier. CI runs all of them.
- The full loop is still `just tests::check` and still `./check.sh`.

## Links

- `check.sh` — the fast lane and the full loop
- `docs/testing/gate-costs.md` — what the loop costs
- `docs/architecture/afk-runner.md` — the contract the runner copies
- `.agents/skills/code-review-pr-workflow/SKILL.md` — the gate the agent runs
- `scripts/test-impact-map.py` — the selection the lane runs
