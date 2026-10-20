---
title: "The instrumented tier gets a smoke on every pull request, and it stays advisory"
date: 2026-10-20
status: accepted
tags: [ci, android, testing, tooling]
---

## Context

The instrumentation tier — `:androidApp:connectedDebugAndroidTest` — is the only
verification surface in this repository that runs the app on a real Android runtime.
`2026-10-07-the-instrumentation-tier-ran-in-no-ci-job.md` moved it out of "no CI job
at all" and into a workflow of its own, and that workflow runs it on a schedule, on
pushes to `main`, on tags, on manual dispatch, and on a pull request labelled
`run-device-tests`.

The consequence of that arrangement is that **a pull request can break the app on a
device and nothing says so until the next nightly**. The nightly is the first run
that would notice, and it runs at 03:00 UTC, on a timer, on a pull request that has
usually moved on by then. The red nightly is also the least actionable red signal in
this repository: by the time it arrives, the change that caused it is buried under
whatever landed next.

There is a second, quieter half to the same problem. The instrumented source set is
compiled by nothing in the pull-request pipeline. The Android leg of `ci.yml` runs
`:androidApp:compileDebugKotlin` and stops there, deliberately — the APK is packaged
in `e2e.yml` and in the device workflow, and assembling it twice in two workflows was
waste. But the *androidTest* source set is packaged nowhere on a pull request, so a
broken instrumented test is not a compile error on a pull request either: it is a
red nightly, days later, for a reason that has nothing to do with whatever was
under test.

Both halves are the same defect seen from two ends: **a tier that only runs on a
timer is a tier nobody is watching.**

The obvious counter-argument is the one that has been in the workflow header since
the beginning: an emulator adds roughly ten minutes and is the flakiest part of any
Android CI, so it must not gate a pull request. That argument has not changed. What
has changed is that "does not gate" and "does not run" are two different decisions,
and only the first one was ever necessary.

## Idea

- **A.** Leave it as it is. The tier runs on a timer and on request; the pull
  request pipeline stays free of emulators.
- **B.** Run the full instrumented suite on every pull request. Every push pays
  ten minutes, and a flaky emulator run now blocks a merge.
- **C.** Run one class on every pull request, advisory, and keep the full suite on
  the timer, on `main`, on tags and on the label. The pull request gets a device
  signal; the merge decision does not depend on an emulator.
- **D.** Compile the instrumented source set on every pull request, and leave the
  *running* of it on the timer.

## Decision

**C and D together** — the two halves of the same defect, addressed by the two
cheapest measures that address them.

- **The smoke tier.** A pull request without the `run-device-tests` label runs
  `AppStartupTest` — three tests: the auth bypass reached the build, the app is not
  on the sign-in screen, and the shell's test tags reach the accessibility tree.
  The step is `continue-on-error: true` and says so in its own name, so it is
  advisory by construction and by declaration: it never fails a merge.
- **The full tier** is unchanged in what it runs and when it runs: the whole
  instrumented suite, on the nightly, on `main`, on tags, on manual dispatch, and on
  any pull request that asks for it with the `run-device-tests` label.
- **The tier is decided once**, in the workflow's `plan` job, which reads the pull
  request's own labels and emits either `tier=smoke` or `tier=full`. Every later
  step reads that output; no step decides the tier for itself.
- **The instrumented source set is compiled on every pull request**:
  `:androidApp:compileDebugAndroidTestKotlin` joins the free leg of the Android
  matrix in `ci.yml`. It costs a Kotlin compile and no emulator, and it removes the
  "red nightly for an unrelated reason" failure mode entirely.
- **The two pull-request tiers run in separate concurrency groups.** The group key
  carries the label name, so an automatic smoke run and a labelled full run are
  different measurements and neither cancels the other. Without that, a push to a
  labelled pull request would cancel the full run in favour of the advisory one —
  the authoritative run dying in favour of the smoke, which is exactly backwards.

## Rationale

- **A pull request that breaks the app on a device should say so while the pull
  request is still open.** The nightly answers the question days late, to nobody in
  particular, about a commit that is no longer the head. The smoke answers it in
  ten minutes, on the commit that is actually under review.
- **Advisory is the honest status for an emulator run.** A gate that fails on a
  flaky emulator teaches people to re-run it, and a re-run that usually passes
  teaches people to ignore it. The smoke's value is entirely in being read; a
  signal that gets ignored has negative value, because it looks like coverage.
  The step therefore declares itself advisory in its name, where Part D of
  `scripts/check-gate-wiring.py` can see it and where a reader of the workflow
  will see it too.
- **The smoke class is the one that answers the question that matters.** "Does the
  app start, and is the shell reachable?" is the question every other failure
  presupposes the answer to. A smoke that exercised the settings screen instead
  would cost the same and answer less.
- **Compiling the instrumented sources is nearly free and removes a whole class of
  confusing red.** The device tier runs rarely enough that a broken instrumented
  test would otherwise be discovered by the nightly, blamed on the nightly, and
  fixed days later. A Kotlin compile catches it in the pull request that caused
  it, in the place where the cause is.
- **B is the obvious answer and the wrong one.** Ten minutes on every push, with
  the flakiness of the emulator deciding whether merges are possible, is a worse
  arrangement than a nightly plus a label. The cheap surfaces gate the pull
  request; that has not changed, and this decision does not change it.
- **D alone would have been cheaper still**, and is not enough: it keeps the
  instrumented tier's *results* stale, which is the larger half of the problem.
  C alone would keep the instrumented source set uncompiled on a pull request,
  which is the smaller half. Neither half closes the other.

## Consequences

- Every push to a pull request now boots an emulator. That is a real cost in
  runner minutes, and it is the price of the signal. It is spent knowingly, and
  it is bounded: one class, one activity launch each.
- **The smoke tier does not measure the suite.** It runs one class by construction,
  so its report is below the instrumented floor, and the floor check is therefore
  skipped in that tier. A floor measured against a deliberately partial run would
  report the smoke as a regression of the suite.
- **The `run-device-tests` label is now the only way to ask for the full tier on a
  pull request**, and it is repository metadata rather than a file. It can go
  missing, and when it does, the workflow does not fail — it computes the smoke
  tier and a pull request can wait for a full run that will never start. The
  symptom is invisible, which is why `docs/testing/android-instrumentation-runbook.md`
  says how to check for the label and how to recreate it.
- The instrumented source set is compiled on every pull request, so a broken
  instrumented test fails the Android leg of `ci.yml` — a job that is already a
  required check. That is the intended effect, and it is a new way for the Android
  leg to go red.
- The two tiers share the job's device settings — the AVD, the GPU mode and the
  boot budget are job-level, not step-level. A smoke that booted a different AVD
  would be answering a different question from the full run, and the difference
  would be invisible.

## Links

- `.github/workflows/android-device-tests.yml` — the tier decision itself
- `.github/workflows/ci.yml` — the instrumented source set's compile
- `docs/testing/android-instrumentation-runbook.md` — the work order for this tier
- `docs/testing/android-tier-runbook.md` — the Maestro side of the Android story
- `2026-10-07-the-instrumentation-tier-ran-in-no-ci-job.md` — why the tier is a
  separate workflow rather than a registry gate
- `2026-10-20-android-failure-bundle-travels-through-the-shell.md` — the evidence
  a red device run leaves behind
