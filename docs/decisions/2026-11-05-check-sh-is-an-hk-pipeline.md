---
title: "The local verification loop is declared in hk.pkl, and check.sh is its entry point"
date: 2026-11-05
status: accepted
tags: ["tooling", "gates", "ci"]
---

# The local verification loop is declared in hk.pkl, and check.sh is its entry point

## Context

`check.sh` was 720 lines of bash. It held the order of the loop, the three
lanes (`--fast`, `--affected`, the full loop), the per-step timing that feeds
`docs/testing/gate-costs.md`, the colour and message of every failure, the
optional device steps, and the exit-code contract of the test-run floor
(`3` = no JUnit report, `1` = below the floor).

Three things followed from that shape, and all three were paid repeatedly:

- **The steps existed in two places.** CI's jobs spell the Gradle invocations
  their own way, and `scripts/check-gate-wiring.py` existed to keep the two
  from drifting. Parts A, E and I of that gate read `check.sh` as text.
- **Adding a step was a bash edit**, with a `timed` wrapper, a banner, an
  error message and — for the fast lane — a second place to add or omit it.
- **The step names were the contract.** The cost report, the published
  baseline and the tests of the loop all keyed on the names `timed` recorded.

`hk` was already the repository's hook orchestrator (`hk.pkl`, generated from
`mise.toml`), and its CI story is exactly this shape: declare the steps, then
run `hk check --all` (https://hk.jdx.dev/ci.html).

## Idea

- Move the steps into `hk.pkl` as the `check` hook and let `check.sh` be the
  local entry point that picks a lane.
- Express the lanes with hk profiles, not with `if` branches.
- Keep `check.sh` as the command every caller already uses (`just`, the
  skills, the PR template) — replacing the name as well would churn every
  document for no gain.
- Keep the steps serial: two Gradle invocations at once fight over the shared
  module cache and the daemon, which is why the loop was sequential.

## Decision

`hk.pkl` declares the `check` hook. Every step is `exclusive`, so hk runs them
one at a time in declaration order, and `fail_fast` stops the run at the first
failure — the same order and the same stopping rule as the shell loop.

The lanes are profiles, and the wrapper passes exactly one:

| Profile | What runs |
| --- | --- |
| `full` | `./check.sh` — the whole loop |
| `affected` | the whole loop, with the affected tests and the changed-files detekt instead of the suite and the module sweep |
| `fast` | the checks a change can break: ktlint, the Android compile, the affected tests, the changed-files detekt, the static gates |

A step with no `profiles` runs in every lane (ktlint, the static gates, the
Android compile). A step with `profiles = List("full")` runs only in the full
lane (the JVM suite, the test-run floor, the module detekt sweep). A step with
`profiles = List("!fast")` runs everywhere but the fast lane (the architecture,
Android-host, desktop and ADR suites, `assembleDebug`, the gate-machinery
steps). The two affected variants use `profiles = List("!full")`.

`check.sh` keeps what is not a check: `mise install`, the flag-to-profile
mapping, the cost record, and the device steps (adb instrumentation and
Maestro) — those need hardware, are opt-in, and are not this loop's verdict.

The cost record moves with the timing: `check.sh` sets `HK_TIMING_JSON`, and
converts hk's per-step wall times into the `name<TAB>milliseconds` file
`sg gate-costs` reads. The step names are unchanged, so the published baseline
and `docs/testing/gate-costs.md` keep speaking about the same steps.

The gates that read the loop now read `hk.pkl` (and `check.sh` for the device
step): `check-gate-wiring.py` (Parts A/E/H/I and the registry check),
`check-gradle-test-population.py`, and `check-waiver-references.py`.

## Rationale

**One declaration, and the order is visible.** The step list, its order and its
lane membership are one file, and the file is a declaration rather than a
program. A step added there runs in the loop; a step removed there is gone from
the loop and from every reader of the loop.

**The timing is hk's, not a shell function's.** `HK_TIMING_JSON` records the
wall time of every step hk runs, including a step that failed. The converter is
eight lines and runs on every exit path, so a failed loop is still measured.

**Serialization is a property of the steps, not of the runner.** `exclusive`
states why the steps cannot overlap; a future step that can overlap simply
omits it.

**`--run` had to learn `--rerun` first.** The affected lane used to `eval` the
commands `test-impact-map.py --command` printed and append `--rerun`, because an
UP-TO-DATE test task runs no test. `hk` cannot `eval` a multi-line selection, so
the flag moved into the tool it belongs to: `--run` now re-runs the task.

## Consequences

- `./check.sh`, `./check.sh --fast` and `./check.sh --affected` behave as they
  did. The flags map to profiles; the steps are the same commands in the same
  order.
- Step output is captured by hk and printed under the step's name, instead of
  streaming live as the shell loop printed it. The step names are the same, so
  a reader who knew "shared:jvmTest" still finds it.
- The test-run floor's exit code `3` ("no JUnit report") is now a step failure
  like any other; the message the script prints is still the one that says
  which of the two it was. The wrapper no longer re-exports `3`.
- The device steps stay in `check.sh`: they are opt-in, need hardware, and
  their failure is not the loop's verdict.
- CI can run the same declaration with `hk check --all` if a job wants it; the
  existing jobs are unchanged and keep their own Gradle invocations.
- `check-gate-wiring.py`, `check-gradle-test-population.py` and
  `check-waiver-references.py` read `hk.pkl` as the local surface. A new step
  that names a Gradle task is therefore visible to the parity gate, and a task
  dropped from the loop is reported.

## Links

- `hk.pkl`, `scripts/generate-hk-pkl.sh`, `check.sh`
- `scripts/test-impact-map.py` (`--run` now passes `--rerun`)
- `scripts/check-gate-wiring.py`, `scripts/check-gradle-test-population.py`,
  `scripts/check-waiver-references.py`
- `scripts/tests/test_check_sh_format_step.py` (reads the ktlint step out of
  `hk.pkl`)
- `docs/decisions/2026-11-05-the-commit-runs-hk-steps-over-staged-files.md`
  (the same orchestrator, one event earlier)
- https://hk.jdx.dev/ci.html
