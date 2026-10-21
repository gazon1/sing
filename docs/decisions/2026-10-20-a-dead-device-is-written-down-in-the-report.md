---
title: "A dead device is written down in the report, not in the process output"
date: 2026-10-20
status: accepted
tags: ["architecture", "e2e", "maestro", "tooling"]
---

## Context

The runner decided whether a failed flow was worth retrying by looking at one
place:

    if not deps.maestro_saw_device_failure(stderr) and deps.device_alive(serial):
        return False, run_id     # a broken flow — counted, not retried

`stderr` was the wrong place. When the device dies under a flow, Maestro
writes it into the flow's **JUnit report**:

    <failure>maestro.android.DeviceServerDiedException: Device server died
    during 'deviceInfo' on emulator-5554: StatusRuntimeException: UNAVAILABLE
        at maestro.android.AndroidDeviceConnection.onGrpcDeath</failure>

and the process's stderr stays empty. The flow never ran — Maestro died while
asking the device what it is — so there is no assertion to report and nothing
for stderr to carry.

The consequence was a miscount, not a delay. A dead device was counted as a
broken flow: the suite reported a defect that does not exist, the flow was not
retried even though the device was alive a second later, and the report said
"40 flows failed" about a device that had flickered.

The recovery path already existed and was correct — a device-level signature
with a device that answers means a busy host, and the answer is a plain retry,
not an adb-server reset. It simply never ran, because the signal was never
found.

## Idea

- **A.** Read the JUnit report as well as stderr.
- **B.** Make Maestro's process output carry the failure (patch the tool).
- **C.** Count every failed flow the same way and report device deaths
  afterwards.

## Decision

**A** — a second seam, `junit_reports_device_failure`, reads the flow's own
report.

Two details make it safe:

- **The report is read per attempt, and the previous attempt's report is
  deleted first.** A leftover report would make the next attempt look like it
  failed the way the previous one did, and "the previous one died with the
  device" is exactly the difference between retrying and giving up.
- **The patterns for the report are tighter than the ones for stderr.** The
  report also carries the app's own text — an assertion may legitimately say
  "Service unavailable" — and a loose pattern would read that as a dead
  device and retry a flow that simply failed its assertion. The report is
  matched against Maestro's own names: `DeviceServerDiedException`,
  `Device server died`, `StatusRuntimeException: UNAVAILABLE`,
  `maestro.android.AndroidDeviceConnection`.

## Rationale

- **The report is where the truth already is.** No new instrumentation, no
  change to Maestro, no second opinion: the evidence was being written down
  and not read.
- **A device that dies and a flow that fails are different facts.** One is
  about the environment, one is about the app. Counting them alike means the
  suite's verdict is ambiguous, and an ambiguous verdict is not a verdict.
- **B is not available.** Maestro is a released tool; its process output is
  what it is.
- **C is the defect itself.** Sorting the failures out afterwards cannot
  recover the retried flows: the suite's own exit code, and the report it
  prints, have already been produced.
- **The tight patterns matter because a false "device failure" is expensive.**
  It retries a flow that failed for a real reason, which hides the defect the
  flow was written to catch.

## Consequences

- **A device-level failure is now retried even when stderr is silent**, and the
  retry does not reset the adb server while the device answers.
- **A broken flow is still a broken flow.** The report's assertion failure is
  not a device failure, so the flow is counted and not retried.
- **Two files read the same evidence in different vocabularies.** The stderr
  patterns stay broad — that output is Maestro's, not the app's — and the
  report patterns name Maestro's classes.
- **`scripts/tests/test_maestro_device_failure.py` covers both directions**:
  a dead device written only to the report is retried, an assertion failure is
  not, and an assertion that says "unavailable" is not mistaken for a dead
  device.

## Links

- `scripts/src/emulator/maestro/_device.py` — `junit_reports_device_failure`
  and the two pattern sets
- `scripts/src/emulator/maestro/_runner.py` — the retry decision and the
  per-attempt report deletion
- `scripts/tests/test_maestro_device_failure.py` — the two directions
- `2026-10-20-the-local-e2e-run-measures-the-binary-ci-measures.md` — the
  other measurement defect of the same run
