---
title: A floor for never-run cases, and what a rotation must not destroy
date: 2026-10-05
status: accepted
tags: [kiwi, tcm, testing, gates, infra]
---

## Context

`infra/kiwi/` (ADR `2026-10-05-kiwi-tcms-stand-and-non-test-files`) made the
question "which tests have I never actually run?" answerable, but explicitly as an
*instrument*: nothing could fail a build on it. Two floors existed for everything
else — `check-coverage.py` for reached code, `check-test-runs.py` for executed
counts — and Kiwi fed neither. A green pipeline therefore said nothing about the
one failure mode Kiwi exists to see: a test that was written, tagged, and never
selected by any run.

Making it a gate raised two problems that are not about Kiwi at all.

**A floor needs a direction, and this one is inverted.** The other floors record
the *smallest* legitimate value. Never-run cases are a *ceiling* — the highest
count a legitimate state produces. Recording it any other way produces a gate
that fails forever, or one that gets regenerated downward until it means nothing.

**A rotation and a floor over the same data cannot both be right.** The obvious
floor is "cases with a live TestExecution". `prune` deletes runs, `TestRun.remove`
cascades to executions, and the count jumps — measured on the stand: 47 → 237
never-run cases after `prune --keep 1`, with **no test changing and every test
still passing**. The floor was measuring "not run since the last rotation".

## Idea

Three options were available for what the floor counts.

1. Cases with no live execution — breaks on every rotation, as measured.
2. Cases whose most recent run FAILED — a different and also useful gate, but it
   catches nothing here, because a case with no runs at all is not "failing".
3. Cases that have **never** been executed in the life of the stand, recorded on
   the case itself and therefore not owned by any run.

Option 3 separates the two facts: *how recently did this run* (run history, which
rotation is free to discard) and *has this ever run* (the case's own record, which
is not).

## Decision

**1. The floor is a per-plan ceiling, and it fails only on a rise.**

Per-plan because a single aggregate is not a floor. Moving a test from `shared` to
`desktopApp` changes the total by zero while changing both per-plan numbers; a sum
hides the move, two opposite deltas make it visible.

Fails only on a rise because the other direction is not a defect. Fewer never-run
cases usually means results stopped arriving — stale `build/test-results`, or a
test task that was UP-TO-DATE and never rewrote its XML. That is a tooling fault
to investigate, not a coverage gain, and failing on it would teach the reader that
the gate is noise. A drop prints as a note.

A plan with no floor is skipped rather than failed: it has no legitimate run to
record a floor from, and inventing one produces either a gate that fails forever
or a number mistaken for a measured minimum.

**2. "Never run" is `ever_run` on the TestCase, written by `sync.py --results`.**

`TestRun.remove` cascades to `executions`, `tags`, `cc_list` and the run's own
`property` — and touches neither `TestCase` nor the properties on it. The marker
therefore survives rotation, which is the whole point. The value is a fixed `"1"`
on purpose: Kiwi's `add_property` is `get_or_create(case, name, value)`, so a
per-run value would add a row on every sync and make the case grow without bound.

**3. `--max-age` is a separate flag, not a default.**

Freshness is what makes a floor meaningful, but forcing it would fail every local
loop that has not synced today. `--max-age` is opt-in, and the default run is a
comparison against whatever the stand last recorded.

**4. Rotation defaults to doing nothing.**

`prune.py` prints what it would delete unless given `--apply`. Deleting run history
is irreversible, and the failure mode of getting the window wrong is not a loud
error — it is history that quietly stops existing.

**5. Kiwi cannot be fed from CI, and that is recorded rather than papered over.**

The stand is on `127.0.0.1` of a private LAN host; every CI job runs on an
ephemeral GitHub-hosted runner, there is no self-hosted runner registered, and the
repository's runner-registration API returns 404 for this token. A `kresults` step
in `ci.yml` would fail at connect time. The options — a self-hosted runner on this
host, exposing the stand through a tunnel, or a scheduled local sync — each cost
something the owner has to weigh, so none was chosen unilaterally. Until one is,
**this gate does not protect `main`**, and the README says so in those words.

## Rationale

The measurement was the hard part, and it was settled by running the thing rather
than by reasoning about it. Both the 47 → 237 jump and the counter-intuitive
result where a partial Gradle run (`--tests "…arch.*"` rewriting the results
directory down to 23 files) was caught by the gate instead of being reported as
health, came from executing against the stand. The second one is the gate
working: a stale-results situation that `check-test-runs.py` handles with
`--since`, here showed up as 237 never-run cases and a red gate.

The unfiltered `TestExecution.filter({})` in `gaps.py` was fixed for the same
reason. It returned every execution in the stand's history, including runs
already deleted by rotation, so its cost grew with every sync while its output
stayed correct — the kind of regression that is invisible until it is expensive.

The `--apply` default and the per-plan ceiling both come down to the same thing:
this gate's numbers are only trustworthy if the things it deletes are gone and the
things it ignores are recorded.

## Consequences

`config/docs/kiwi-gaps-baseline.txt` joins the other baselines, with a header that
says "ceiling" and "HIGHEST" — deliberately the opposite of every other baseline in
the repo, because a reader who does not notice will regenerate it downward.

`sync.py` now writes one property per executed case, so a sync of 200 cases costs
200 extra RPC calls. Measured acceptable at this size; if it matters, the bulk
property API already in the client is where to batch it.

`just kprune` is a dry run by default. `just kprune apply` is the destructive form,
and it is spelled differently on purpose.

The gate needs a running stand, so it skips on `--if-present` in a local loop and
**fails** without it in any context where the stand is supposed to be up. A gate
that skips silently everywhere would be the exact defect class this repo keeps
recording.

## Links

- `scripts/check-kiwi-gaps.py`, `config/docs/kiwi-gaps-baseline.txt`
- `infra/kiwi/prune.py`, `infra/kiwi/gaps.py` (`TestExecution.filter` now filtered)
- `infra/kiwi/README.md` — "Гейт на дыры и ротация", "Почему в CI это не работает"
- `infra/kiwi/sync.py` — writes `ever_run`
- `scripts/tests/test_check_kiwi_gaps.py`, `scripts/tests/test_kiwi_prune.py`
