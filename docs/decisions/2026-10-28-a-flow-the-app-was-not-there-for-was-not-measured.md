---
title: "A flow the app was not there for was not measured, and a run can be resumed"
date: 2026-10-28
status: accepted
tags: [testing, maestro, android, reporting]
---

# A flow the app was not there for was not measured, and a run can be resumed

## Context

The runner recovered from a device that stopped answering and then carried
on with the next flow
(`2026-10-28-a-lost-device-ends-the-run.md`). That rule covers a device
that is gone. It does not cover a device that is perfectly healthy and no
longer has the app on it.

Measured on a physical phone (2026-10-28): at 15:59 the package was removed
from the device — the phone's own package manager records
`com.singularity.todo` being removed — and the device never disconnected.
The runner kept going. Twenty-six flows failed with `Package
com.singularity.todo is not installed` and five more with `Unknown error`.
The run reported 55 failures out of 94 flows, of which 31 were not
measurements at all.

The accounting was wrong in the way that matters. A failing flow is a
statement about the app; a missing app is the absence of the app, and
nothing about the app follows from it. Reporting the second as the first
would have made a gate count the runner's blind spot as the app's debt —
and the floor would have had to be raised to cover it.

Two smaller costs sat on top of that. The run was not resumable: ninety-four
flows take about two hours, and one missing package threw away every one of
them, including the sixty-three that had been measured properly. And a flow
that passed on its second attempt was reported exactly like a flow that
passed on its first, so the evidence that a third of the suite is not
reliable was thrown away.

## Idea

A run has three kinds of outcome, not two: the flow passed, the flow
failed, and the flow was not measured. The third one has to be visible, and
a run that has it must be able to continue.

## Decision

**The app is checked before every flow and after every failure.** A flow
that starts against a device without the app, and a flow that ends with the
app gone, raise `AppMissingError` instead of returning a verdict.

**A flow that was not measured leaves no report.** Maestro's report says
"Package … is not installed", and a report is read as a verdict. The report
is removed, so the gate's own rule — a flow with no report is a flow that
did not run — is the honest shape for it.

**The app is put back and the run carries on.** Unlike a lost device, a
missing app is recoverable: the APK is installed again and the flows after
it are measured properly. The flow that found the app missing stays
unmeasured — reinstalling afterwards does not turn it into a verdict. If
the app cannot be put back, the run stops and names the flows that did not
run, exactly as a lost device does.

**The run records what it did not measure.** The summary carries
`not_measured` beside `passed` and `failed`, and a run with an unmeasured
flow is not a green run.

**The run says where it happened.** The runner writes
`build/maestro-results/environment.json` — the kind of device, its API
level, its model, the serial and the build. The gate reads it and refuses
to compare a run with a floor that was calibrated elsewhere
(`2026-10-28-the-measurement-names-its-causes.md`).

**A run can be resumed.** `--resume` skips the flows that already have a
clean report and runs the rest. The reports are the record: there is no
second list that could disagree with them, and a report that carries a
failure is not a finished flow, so that one is run again.

**A flow that needed more than one attempt is named separately.** The
attempt count is part of the verdict, and it is not the same fact as the
verdict: a flow that passes on the second try is a passing flow and also a
flow that is not reliable, and merging the two loses the second one. The
retried flows are reported beside the failures — they are not debt, and the
floor does not move for them.

## Rationale

The runner had one way to say "this flow did not work", and it used it for
everything the device could do. A measurement that cannot distinguish "the
app said no" from "the app was not there" is not a measurement, it is a
count of attempts.

The report is the interface between the runner and the gate, so what the
report says has to be true. Removing the report of an unmeasured flow is
the only way to make it true: a report is a verdict by construction, and
the gate's rule for a missing report already says the right thing — the
flow did not run.

Resuming is worth its own rule because the alternative is punishing the
reader for the runner's accident. Two hours of work is not a small thing to
throw away because the package was removed at minute eighty.

Naming the retried flows costs nothing and keeps a fact the runner already
has: a suite where a third of the flows need a second attempt is not a
suite that passes.

## Consequences

- The runner asks the device one more question per flow. That is one adb
  round trip, and it is cheaper than the misreading it prevents.
- A run on a device that keeps losing the app now produces a report that
  says which flows were not measured, and a gate that fails on them.
- `--resume` is a decision the caller makes: the default run still measures
  everything, because a run that silently skips what it was asked to do is
  the failure mode this whole round is about.
- The environment file is now part of what a run produces. A third-party
  runner that does not produce it cannot be judged by the gate.
- The attempt count is in the runner's return value, which changed the
  signature of `run_one_flow`.

## Links

- `scripts/src/emulator/maestro/_runner.py` — `AppMissingError`, the
  attempt count.
- `scripts/src/emulator/maestro/_cli.py` — the recovery, the resume, the
  environment file, the summary.
- `scripts/tests/test_maestro_missing_app.py` — the rules, one test each.
- `docs/decisions/2026-10-28-a-lost-device-ends-the-run.md` — the
  neighbouring rule: a device that is gone.
- `docs/decisions/2026-10-28-the-measurement-names-its-causes.md` — the
  other half: what the gate does with the report.
