---
title: "maestro-runner is the second engine, and the CLI stays the default"
date: 2026-11-17
status: accepted
tags: [testing, maestro, android, tooling]
---

# maestro-runner is the second engine, and the CLI stays the default

## Context

The suite is 127 flows, and the runner executes each of them as its own
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

## Consequences

- A run can be made faster without changing what its verdict means. The
  gates read the same reports either way.
- The engine that produced a verdict is named in the log, and the build that
  produced it is named too, so a number can be traced to the thing that
  measured it.
- The runner is not in `mise.toml`, so CI cannot pick it up from the
  toolchain file. An installation step is needed before CI can use it.
- The equivalence measured here covers five flows. The full 127-flow suite
  under maestro-runner is not measured in this record.

## Links

- https://github.com/devicelab-dev/maestro-runner — the runner
- `scripts/src/emulator/maestro/_engine.py` — the two commands, the report
  normalisation, the distribution lookup
- `Maestro/README.md` — how to install the distribution and how to pick the
  engine
- `docs/decisions/2026-09-29-emulator-crash-recovery-runner.md` — why each
  flow runs as its own invocation
