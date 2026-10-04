---
title: "One recipe that means \"the gates are green\""
date: 2026-10-04
status: accepted
tags: [testing, ci, tooling, process]
---

# One recipe that means "the gates are green"

## Context

The project has more gates than any single command runs:

| Gate | Command | Runs in `check.sh`? |
|------|---------|----------------------|
| unit + Android assemble | `./check.sh` | — (it *is* check.sh) |
| detekt | `just lint` | yes, step 7 |
| coverage floors | `just cr` | no |
| Maestro agenda flows | `just gm agenda` | opt-in (`RUN_MAESTRO=1`) |
| Maestro smoke flows | (did not exist) | no |
| doc sizes / dead refs | `just docs-audit` | no |
| unwired surfaces | `python3 scripts/find-unwired-surfaces.py` | no |

So "I ran the gates" was not a claim anyone could check. It was shorthand for
whichever subset the speaker remembered, and a partial run looked exactly like a
full one from the outside. Two failures in this epic came from that gap rather
than from a missing check: a stale APK was tested because the install step had
been skipped for speed, and the `maestro-smoke` CI job shipped unrun.

## Idea

- **A.** Document the full set and trust people to follow it.
- **B.** Add a `just gate` recipe that runs them in order and stops at the first
  red.
- **C.** Fold all of it into `check.sh`.

## Decision

**B**: `just gate` = `check.sh` → detekt → `just cr` → `just gm agenda`.

`SKIP_MAESTRO=1` skips the last step, and the recipe says so in its own output
rather than exiting quietly — a run that skipped the flows did not gate the
flows, and a silent skip is how that gets forgotten.

`check.sh` is **not** the place for this. It runs on every change and its cost is
already ~6 minutes; the coverage ratchet alone adds ~9 (instrumented), and the
flows need a booted emulator. A gate that is too slow to run is not run, which
would be a regression against the opt-in Maestro step that `check.sh` already has.

Two details that are the point of the recipe rather than decoration:

- **Each step prints its own banner.** A 20-minute script that fails silently
  halfway leaves you guessing which half. The stale-APK incident was
  indistinguishable from a correct run because there was no visible boundary.
- **The steps stop at the first failure** (`set -euo pipefail`). A gate that runs
  everything and reports a wall of red at the end trains people to scroll to the
  bottom, which is how the real failure gets missed.

`detekt` is listed separately from `check.sh` even though `check.sh` already
runs it. That is deliberate duplication: the two claims are not the same, and
the day they drift apart this is where the drift shows up.

## Rationale

A gate's value is not that it exists, it is that running it is the path of least
resistance. Four commands in an unspecified order is a decision; one command is
a habit. The recipe buys the habit.

It also gives the retro-gate something concrete to point at. "Run `just gate`"
is checkable in a way that "run the gates" never was, which is what makes a
branch's state a fact rather than a claim.

Duplication was preferred over cleverness: the recipe composes existing
commands rather than reimplementing their steps, so there is no second copy of a
gate to drift.

## Consequences

- `AGENTS.md` documents `just gate` first, before the individual commands.
- The Maestro step is the only one with a skip, and it announces the skip.
- The `smoke` Maestro set is **not** in `gate`. It is the CI job's set
  (`maestro-smoke`), and 19 flows is a lot of device time for a local loop. When
  `maestro-ci-job-unproven` closes and the set is trusted, adding it is one line.
- A new gate has one obvious place to register, which is the property that
  keeps this recipe from rotting into another partial list.
- `just gate` does not run `docs-audit` or `find-unwired-surfaces` yet. Both are
  fast and both are in CI; they belong here once this recipe has proven itself
  as the thing people actually run.

## Links

- `.just/tests/mod.just` — `gate`, `gate-maestro`, `coverage-ratchet`
- `justfile` — `alias gate := tests::gate`
- `docs/decisions/deferred-backlog.md` — `maestro-gate-can-test-a-stale-apk`,
  `maestro-ci-job-unproven`
