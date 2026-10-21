---
title: "A lost device ends the run; the flows that did not run are not failures"
date: 2026-10-28
status: accepted
tags: [testing, maestro, android, reporting]
---

# A lost device ends the run; the flows that did not run are not failures

## Context

The runner recovered from a device that stopped answering and then carried
on with the next flow. Measured on a physical phone (2026-10-28), the
wireless adb connection dropped on the seventh flow: the recovery could not
re-establish it (a pinned physical device is never an AVD problem), and the
runner went on to fail every remaining flow in about four seconds each. A
94-flow run produced roughly ninety failures and a report that read as a
broken suite.

The runner was not wrong about the individual flows — it really did not run
them — but the *accounting* was wrong in the way that matters: a lost device
is a statement about the run, and a broken flow is a statement about the app.
Reporting the second as the first hides the cause under ninety symptoms, and
the reader of the report has no way to tell the two apart.

The same round found the selection half of the same story: the `just`
recipes pass what to run through the environment (`SERIAL`, `FLOW`, `TAGS`),
and the runner read only `SERIAL`. `just gm agenda` ran all 94 flows
instead of the 8 agenda-tagged ones, and said nothing about it.

## Idea

Two claims the runner makes about a run have to be true, and both are
checkable at the point where the runner makes them:

- the run ran the flows it was asked to run, and
- the run's failures are statements about the app.

## Decision

**The environment selects the run.** `SERIAL`, `FLOW` and `TAGS` are read
from the environment, as the recipes pass them. An explicit flag still wins
over an inherited variable: a flag is a decision, a variable is a default.

**A device that cannot be recovered ends the run.** The runner raises
`DeviceLostError`; the CLI stops, says which flows did not run, and exits
without calling them failures. The flows that did run keep their reports.

**A lost device is a different outcome from a broken flow in the report
itself.** The JUnit report of the flow that was running when the device
died says so, and the gate that reads the reports tells the two apart.

## Rationale

Continuing after a lost device cannot produce information. Every remaining
flow would fail the same way, in the same four seconds, for the same reason.
The only thing the continuation produces is a bigger number and a worse
report — and a bigger number is exactly what the new gate reads as "the debt
grew". A gate that cannot tell a lost device from forty broken flows will
fire on a network hiccup and teach people to ignore it.

Stopping is also the honest accounting. The run did not cover 94 flows; it
covered 7. Saying so is what lets the reader trust the number for the flows
that did run.

The environment contract is the same argument from the other side. A
selection that is ignored is worse than a selection that cannot be made: the
report is about a different set of flows than the person believes it is
about, and nothing in the output says so. The test for it is a subprocess,
because the runner reports through kermit and `CliRunner` never sees those
lines — an in-process test would assert on an empty string and pass.

## Consequences

- A run whose device dies reports the flows it covered and names the ones it
  did not. The gate fails on "no report" — which is the same truth from the
  other side.
- `just gm agenda` runs the agenda flows; `just ui-maestro <flow>` runs one
  flow. Both were wrong before, and neither said so.
- The runner stops instead of burning the remaining flows. A run that needs
  the device back has to be started again — which is the point: the flows
  after the gap would otherwise run against a device nobody looked at.
- The contract is pinned by a test that feeds the runner a known environment
  and reads the flows it would run, in both directions (the variable
  selects; the flag wins).

## Links

- `scripts/src/emulator/maestro/_cli.py` — the environment contract and the
  stop
- `scripts/src/emulator/maestro/_runner.py` — `DeviceLostError`
- `scripts/tests/test_maestro_env_selection.py`,
  `scripts/tests/test_maestro_device_failure.py`
- `2026-10-20-a-dead-device-is-written-down-in-the-report.md` — the other
  half of this: a dead device is written into the report
- `2026-10-28-maestro-measurement-gate.md` — the gate that reads the reports
