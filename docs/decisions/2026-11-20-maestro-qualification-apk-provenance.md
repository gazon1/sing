---
title: "Maestro flow qualification — the attempts run this checkout's build"
date: 2026-11-20
status: accepted
tags: [maestro, qualification, runner, evidence]
issue: 3052
---

## Context

The flow runner refuses to run a flow against an installed APK that is not the
checkout's build: it compares the build id read from the device with the
checkout's SHA and exits when they differ. `--allow-stale-apk` is the switch
that turns that check off, and the qualification runner forwarded it.

A qualification could therefore be produced against an older APK. Nothing
downstream noticed: the record carries the build identity, and a stale build
was recorded *consistently* across all three attempts, so the consistency
check passed. The evidence looked complete and named a build that was not the
one the change was about — the qualification certified repeatability of a
different program.

The record is evidence about this checkout. A qualification that ran another
build is not weaker evidence about this one; it is evidence about something
else.

## Idea

1. **Forward the switch, record the build.** The switch is explicit, so the
   developer knows, and the record names the APK that ran the attempts.
2. **Refuse the switch only when recording.** A dry run stays flexible; only
   the evidence path is strict.
3. **The qualification runner never runs another build.** The switch is not
   accepted at all, and the runner that runs the attempt is never told to skip
   the provenance check.

## Decision

**Option 3.**

`scripts/maestro-qualify-flows.py` refuses `--allow-stale-apk` with exit code
2, before the first attempt, and never forwards it to the runner that runs the
attempt. The qualification runner therefore always runs the APK built from the
checkout being qualified, and the provenance check the runner performs is never
switched off.

`--skip-install` stays available: it skips the *install*, not the check. The
runner still compares the installed APK with the checkout's build and refuses
the attempt when they differ.

## Rationale

- **Evidence names the program it certified.** The record's build identity is
  only meaningful when the build is this checkout's; otherwise the field is
  accurate and useless.
- **A run that may produce evidence cannot weaken the rule it stands on.**
  Refusing the switch only when `--record` is passed would leave a dry run
  that prints a record a developer can paste into the evidence file by hand —
  the same hole, one step later.
- **Failing up front is cheap.** The refusal costs one exit code before a
  device run; the alternative costs a qualification that certifies the wrong
  program.
- **The switch still exists where it belongs.** `emulator.maestro` and
  `scripts/run-maestro-e2e.py` keep it for local runs, where the developer
  knows which APK is installed and wants to run against it.

## Consequences

- A developer who wants to exercise the qualification harness against an APK
  that is not this checkout's build cannot; they run the flow through
  `scripts/run-maestro-e2e.py` or the emulator CLI instead.
- The qualification runner has one fewer switch to explain, and the rule
  "the attempts run this checkout's build" holds for every record the runner
  writes.

## Links

- Issue: https://github.com/gazon1/sing/issues/3052
- Policy: `docs/testing/maestro-flow-qualification.md`
- Runner: `scripts/maestro-qualify-flows.py`
- Provenance check: `scripts/src/emulator/maestro/_cli.py`
- Related: `docs/decisions/2026-11-20-maestro-qualification-device-identity.md`,
  `docs/decisions/2026-11-20-maestro-qualification-version-prerequisite.md`
