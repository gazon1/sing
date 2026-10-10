---
title: "Mainactivity Anr Makes Every Instrumented Test Fail"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED — tracked GitHub issue is closed****

**Tracked as:** [#219](https://github.com/gazon1/sing/issues/219)

**Found in:** setting up `android-device-tests.yml` (2026-10-07), while reading
the KDoc on every class in `androidApp/src/androidTest/` before wiring them into a
CI job.

**Symptom:** all four instrumentation classes (`AuthFlowInstrumentedTest`,
`NavigationFlowInstrumentedTest`, `CreateTaskFlowInstrumentedTest`,
`CreateNoteFlowInstrumentedTest`) carry the same warning: `MainActivity` ANRs on
emulator startup, and "all tests will fail on emulator until the Koin/Startup ANR
is resolved". Traced to `koinInject<AppearanceSettingsRepository>()` being called
in the App composable during cold start, per
`docs/decisions/2026-09-28-androidApp-smoke-tests-enabled.md`.

**Already ruled out:** not a stale comment. The cause is still in the tree —
`shared/src/androidMain/kotlin/com/singularity/todo/App.kt:50` reads
`val appearance: AppearanceSettingsRepository = koinInject()`. Two other
`koinInject()` calls sit in the same composable (lines 134, 136). Not ruled out:
whether the ANR still reproduces — that needs a device, and this host had none up.

**Do not fix by deleting the assertion.** All four classes currently assert only
that a `ComposeView` is attached; two of the four KDocs say outright that they are
placeholders that do not exercise the flow in their name. If they are red because
of the ANR, the honest state is red.

**Try next, in this order:**

1. **Run the workflow once on a real runner** and read the actual failure. This
   entry is a claim in a KDoc repeated four times; the first nightly run of
   `android-device-tests.yml` either confirms it or disproves it. Do not spend time
   on the ANR before that measurement.
2. If it reproduces: the question is why `koinInject()` on the composition thread
   at cold start blocks. `AppearanceSettingsRepository` reads a DataStore-backed
   preference, and a suspend read on the main thread during composition is the
   shape that produces `ANR: FocusEvent`. The likely fix is hoisting the read out
   of the composition or making the initial value synchronous.
3. Either way, replace the four placeholder KDocs with the measured outcome. Four
   copies of the same unverified warning is the arrangement
   `2026-10-05-gate-audit-text-shape-vs-fact` was written about.

**Related but separate:** `androidApp/src/androidTest/` contains no test that
exercises the flow in its class name. That is a coverage gap independent of the
ANR, and `thirteen-scenario-slices-queued-not-yet-written` (#170) is the same gap
one tier up.
