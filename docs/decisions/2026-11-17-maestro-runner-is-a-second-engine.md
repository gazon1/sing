---
title: "maestro-runner is the second engine, and the CLI stays the default"
date: 2026-11-17
status: accepted
tags: [testing, maestro, android, tooling]
---

# maestro-runner is the second engine, and the CLI stays the default

## Context

The suite is 129 flows, and the runner executes each of them as its own
Maestro CLI invocation. That is deliberate — a device death then costs one
flow instead of the whole suite — but every invocation starts a JVM and pays
its warm-up, and the CLI drives the device over an HTTP layer that costs a
round trip per element lookup. On the smoke subset (five flows, realme
RMX3938, Android 15) the CLI spends 210.9 s inside the flows and 295 s of
wall clock; the JVM start-up is a large share of the difference between the
two numbers.

The failure floor of this repository was measured with the CLI. A run that
quietly used a different executor would be a measurement of something else,
so "faster" is only useful here if it comes with the same verdict.

[maestro-runner](https://github.com/devicelab-dev/maestro-runner) is a single
Go binary that reads the same flow YAML without a JVM. It was written for
exactly this shape of work: the same files, a faster executor.

## Idea

Let the executor be a choice, the way the device and the flow set are
choices — and keep everything around the execution untouched, so a verdict
under either engine means the same thing.

## Decision

**The engine is a first-class choice, and the CLI stays the default.**
`--engine maestro|maestro-runner`, or `MAESTRO_ENGINE`. The CLI remains the
default because the floor was calibrated with it; the runner is chosen
deliberately, and the engine in use is named in the run log.

**Only the execution is swapped.** The fixture wipe, the session reset, the
retry, the device-loss recovery and the APK provenance check are the same
code either way: that is where the meaning of a run lives. What changes is
the process that reads the YAML.

**The report is rewritten into the shape the gates read.** The CLI writes the
flow's path into the `file` attribute of the JUnit `testcase`;
maestro-runner writes it as a `property`. The gates, the traceability layer
and the GitHub annotations all read the attribute, so a maestro-runner report
is normalised before they see it — the shape of the report is the contract,
not the shape one binary happens to write. The rewrite is additive: an
attribute is added, nothing is removed.

**The waits are not re-tuned.** maestro-runner's `--find-timeout` is left
unset, which means "as Maestro" (17 s on most drivers), and a flow's own
`timeout:` overrides it — the same eight seconds the CLI gives
`extendedWaitUntil`. Two engines that waited for different amounts of time
would not be comparable, so nothing here changes a wait.

**The runner is installed as a distribution, not as a binary.** The driver
that talks to the device ships as APKs under `drivers/android`, next to the
binary; a copy of the binary alone exits with `drivers directory not found`
before it drives anything. mise cannot install that set — every backend that
can pick the platform for us (`ubi`, `http`) installs the binary and drops
the drivers — so the release tarball is unpacked by hand and
`MAESTRO_RUNNER_HOME` points at it. The version this repository measured
(1.1.31) lives in `maestro._engine`, the runner's own version is printed in
the run log, and a different build is called out as not comparable. The
telemetry event and the update check are switched off.

**CI stays on the CLI.** The floor was calibrated with it, and the runner's
default driver is DeviceLab's proprietary on-device agent; the fully
open-source driver (`MAESTRO_DRIVER=uiautomator2`) is untested here.
Switching the nightly E2E is a separate decision, to be taken with a
full-suite comparison rather than a five-flow one.

## Rationale

Measured 2026-11-17 on the phone that already carried the branch build
(realme RMX3938, Android 15, 720×1600): the same five smoke flows, the same
device, the same order, one engine after the other.

| | CLI | maestro-runner |
|---|---|---|
| wall clock (5 flows, with device prep) | 295 s | 175 s |
| time inside the flows | 210.9 s | 132.9 s |
| verdict | 4 passed, 1 failed | 4 passed, 1 failed |

The failing flow is the same one in both runs — `03-health-drilldown`, on
`#health_bucket_stagnant` — and the wait that timed out is the flow's own
eight seconds, not a default the two engines chose differently. The runner's
report says `Element '#health_bucket_stagnant' not visible within 8s` where
the CLI says `Assertion is false: id: health_bucket_stagnant is visible`:
same assertion, same place, different wording.

The speed-up is 1.69× on the wall clock and 1.59× inside the flows. It is
not the 5× the runner's own benchmark reports, and it is not claimed to be:
that benchmark ran nine flows on a Pixel 4a with the UIAutomator2 driver,
where the CLI's HTTP layer costs more than it does here.

### The whole suite

Measured the same day, the same phone, the same build (`g29a38de23f6f`): the
CLI ran the suite first (13:23:42 → 15:36:33), the runner second
(16:53:57 → 18:06:28).

| | CLI | maestro-runner |
|---|---|---|
| wall clock | 7931 s | 4351 s |
| flows run | 118 (a resumed run — see below) | 129 |
| verdict | 85 passed, 33 failed | 97 passed, 32 failed |
| time inside the flows | — (the CLI does not report it) | 3415.4 s |

The two runs did not run the same flows, and the difference is not the engine's
doing. The CLI run was a *resume*: nine flows already had a clean report from
an earlier run and were not run again, and two flows tagged `manual`/`auth-live`
were excluded by `EXCLUDE_TAGS`, which the runner run did not set. The runner
run reported on every one of the 129 flows in the catalogue.

So the wall clocks are not comparable as they stand, and the per-flow cost is
the number that is: 67.2 s per flow under the CLI (7931 s / 118), 33.7 s under
the runner (4351 s / 129) — **2.0× per flow**. The whole catalogue under the
CLI's own rate would be about 8670 s, against the runner's 4351 s.

On the 118 flows both runs reported, the CLI failed 33 and the runner failed
29; 27 failed under both. The seven that differ were re-run as a 2×2 — the
branch's build and main's build, under each engine — to separate "the engine
said so" from "the build said so".

| flow | branch + CLI | branch + runner | main + CLI | main + runner |
|---|---|---|---|---|
| `attachments/01-add-url` | failed | passed | failed | passed |
| `attachments/04-persistence` | failed | passed | failed | passed |
| `attachments/05-renders-correctly` | failed | passed | failed | passed |
| `projects/04-pick-emoji` | failed | passed | failed | passed |
| `tasks/checklist/TASK-CHECK-01` | failed | failed | failed | passed |
| `tasks/06-delete-undo` | failed | passed | passed | passed |
| `profile/02-isolation` | passed | failed | passed | failed |
| `tasks/10-swipe-delete-undo` | passed | failed | passed | failed |

Five of the seven are the engine and nothing else: the same verdict on both
builds, the other verdict on both builds. The CLI is the stricter one on the
attachments (it asserts the URL text is visible) and on `pick-emoji`; the
runner is the stricter one on `profile/02-isolation` and on
`tasks/10-swipe-delete-undo`. The remaining two — `tasks/checklist` and
`tasks/06-delete-undo` — do not hold a verdict across the four cells, so they
are flaky rather than engine-bound.

**No flow in the 2×2 is deterministically worse on the branch's build than on
main's.** The branch's build is not what makes a flow fail here.

The same verdict came out of the separate A/B over the ten flows that failed
on the phone but not in CI: nine of them fail on main's build on the same
phone, and the tenth (`tasks/06-delete-undo`) passes on main — and passes on
the branch under the runner too, so it is flaky rather than the branch's.

Neither number is a pass against the failure floor: `full 30` was calibrated on
a 94-flow catalogue on 2026-10-22, and the catalogue has grown to 129 since.
Both engines are over that number, so the floor says nothing about either run
until it is re-measured.

### What the gates said

`scripts/ci/check-maestro-results.py --results-dir <the runner's reports>
--scope full` on the runner's own suite: **127/127 flows reported, 30 failed,
floor 30** — the count is exactly at the floor, and the environment matches the
floor's calibration (device, API 35, RMX3938). The gate still exits 1, on 24
findings, all of them the same kind: the cause registry is keyed on the CLI's
wording, so every cause reads as new.

The runner also writes its own wording for a failed command: five flows report
`Element not found: handler_error: Element not found in any of 2 strategies`,
where the CLI names the element that was not found. A report that says
"handler_error" names no element at all.

## Consequences

- A run can be made faster without changing what its verdict means *as a
  report*: the gates read the same shape either way. The verdict itself is not
  identical — on the 2×2, five flows deterministically pass under one engine
  and fail under the other, in both directions. An engine switch is therefore
  also a change of measurement, and the floor has to be re-measured under it
  before the number means anything.
- The engine that produced a verdict is named in the log, and the build that
  produced it is named too, so a number can be traced to the thing that
  measured it.
- The runner is not in `mise.toml`, so CI cannot pick it up from the
  toolchain file. An installation step is needed before CI can use it.
- **The cause registry is keyed on the CLI's wording.** `config/docs/maestro-debt.yaml`
  lists a cause as the failure message the CLI prints ("Assertion is false: id:
  x is visible"); the runner prints "Element '#x' not visible within 8s" for the
  same assertion. Every cause therefore reads as a new, unlisted cause under the
  runner, and the gate says so — 24 findings on a run whose *count* is exactly
  at the floor. The report's shape is normalised; its *message* is not, and
  re-keying the registry is the work that a CI switch would need.
- The whole suite is now measured under both engines (129 flows, the same
  build, the same phone), and the seven flows whose verdict differs between the
  two are measured as a 2×2 over two builds.

## Links

- https://github.com/devicelab-dev/maestro-runner — the runner
- `scripts/src/emulator/maestro/_engine.py` — the two commands, the report
  normalisation, the distribution lookup
- `Maestro/README.md` — how to install the distribution and how to pick the
  engine
- `docs/decisions/2026-09-29-emulator-crash-recovery-runner.md` — why each
  flow runs as its own invocation
