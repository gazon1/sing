---
title: "The instrumentation tier ran in no CI job"
date: 2026-10-07
status: accepted
tags: [ci, android, testing, tooling]
---

# The instrumentation tier ran in no CI job

## Context

Setting up emulator testing in CI surfaced two facts. The first turned out to
belong to someone else, and that is part of the record.

**`main` did not compile for Android** when this work started:
`:shared:compileAndroidMain` failed with two errors in one file,
`AndroidBackgroundWorkScheduler.kt`:

```
:55:21 Argument type mismatch: actual type is 'Long', but 'Duration' was expected.
:96:37 Argument type mismatch: actual type is 'kotlin.time.Duration', but 'java.time.Duration' was expected.
```

WorkManager 2.10.0 speaks `java.time.Duration`; the file passed a `Long` to
`PeriodicWorkRequestBuilder` and a `kotlin.time.Duration` to `setInitialDelay`.
A fix landed independently as `b9a2c503` while this branch was open, and it
converts at the same two call sites. **This change does not carry that fix**, and
the ADR exists partly to say so: the second time two branches found `main`
unbuildable in a place only a gate reaches is a property of the tree, not of
either branch.

**CI had never run at all.** Every job of every run — including runs on `main`
and runs predating this work — completed in two seconds with no runner assigned
and no output. The annotation on the check run says why:

> The job was not started because recent account payments have failed or your
> spending limit needs to be increased.

So the red statuses on PR #217 and #220 carried no information about code. The
compile error was real, but it was found by running the compiler locally, not by
reading CI.

**The instrumentation tier had never run anywhere.** `:androidApp:connectedDebugAndroidTest`
appeared in no workflow. `docs/testing/android-tier-runbook.md` recorded the
consequence in prose — 16 of 32 scenario-matrix cells are holes and the android
column is `not-run` — but prose is not a mechanism, and the trace ratchet had to
carry the exception in `known_gaps` because nothing enforced it.

## Idea

A device tier that only runs locally cannot be a gate, and a local device tier is
not reproducible: whether it passes depends on whose laptop, which AVD, and whether
the host emulator survived the last surface change. Moving it to CI trades a
non-reproducible surface for a slow one. Slow is fixable; non-reproducible is not.

## Decision

1. **New `android-device-tests.yml`, scheduled rather than on every PR.** Nightly
   cron (03:00 UTC), tags `v*`, manual dispatch, and the `run-device-tests` label
   for PRs. The emulator adds ~10 minutes and is the flakiest part of any Android
   CI; `e2e.yml` already made this call for Maestro. The cheap surfaces still gate
   every PR. The cron is one hour off `e2e.yml`'s so the two emulator fleets do not
   compete for the same runner budget.

2. **`connectedDebugAndroidTest`, not `adb install` plus `am instrument`.** The
   Gradle task assembles the app APK and the androidTest APK as a matching pair.
   Hand-rolling the install means naming an APK path, and a path that silently
   stops matching is a green run against the wrong binary — the defect
   `check.sh`'s `SKIP_INSTALL` comment describes for Maestro.

3. **`-gpu swiftshader_indirect`, not `-gpu off`.** `e2e.yml` uses `-gpu off`
   because Maestro drives the UI through the accessibility layer and never
   composites. These Espresso assertions look for a real `ComposeView` in the view
   hierarchy. Different assertion, different requirement.

4. **Reports uploaded unconditionally**, not `if: failure()`. A report that exists
   only when something broke cannot answer "did `NavigationFlowInstrumentedTest`
   pass on `abc1234`", which is what a nightly gets asked the next morning. A
   failed nightly opens an issue, matching `e2e.yml`.

5. **This workflow is not registered in the gate registry, and cannot be.**
   `scripts/ci/static-gates.sh` runs on a runner with no emulator and no build
   output. A gate that reports on a device it cannot reach is the defect
   `a-gate-that-lies-is-worse-than-no-gate` describes, so the honest arrangement
   is a separate workflow rather than a registry entry that could only ever be
   advisory.

## Consequences

- The android scenario column moves from `not-run` to a real signal. The ratchet's
  `known_gaps` entry stays — the matrix normalises `--targets desktop`, and this
  workflow's results are instrumentation reports, not Maestro scenario results.
  Closing that gap needs the flows tagged, not this job.
- **Not yet proven: that the instrumentation tests pass at all.** All four classes
  in `androidApp/src/androidTest/` carry a KDoc warning that `MainActivity` ANRs on
  emulator startup, traced to `koinInject<AppearanceSettingsRepository>()` at cold
  start (`App.kt:50`, androidMain — still present). This host has no running
  device, so that claim is carried forward, not resolved. The first nightly run is
  the measurement. If it is red, the cause is that ANR and not this workflow.
- The emulator tier remains slower than every other surface combined. That is the
  price of the only tier that exercises a real Android runtime, and it is why it
  does not gate a PR.

## Links

- `scripts/ci/static-gates.sh` — the shared registry both CI surfaces call
- `docs/testing/android-tier-runbook.md` — the Maestro-side work order for the
  same column of the same matrix
- `docs/decisions/2026-09-28-androidApp-smoke-tests-enabled.md` — the ANR and the
  Espresso-over-Compose-test-API decision
- `openspec/changes/scenario-results-are-authoritative-in-ci/` — #149, #150