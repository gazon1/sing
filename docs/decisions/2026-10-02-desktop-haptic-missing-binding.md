---
date: 2026-10-02
status: accepted
tags: [incident, desktop, di, testing]
---

# Desktop test suite hangs — task detail composition crashed on missing Haptic binding

## Timeline

- 2026-10-01, ~19:50 — rebase of `refactor/cluster-9-repos` onto `origin/main` brought in
  the celebration-overlay MR (`c91f9eaa`, feat: notes↔tasks logbook, typed deps, celebration overlay).
- 2026-10-01, 20:52 — last known-green desktop test run (pre-rebase code, 51 tests, 0 failures).
- 2026-10-02, ~00:20 — post-rebase `:desktopApp:test` stalled: the `feature.flows.tasks.*`
  group finished **zero** test classes in 40+ minutes while burning CPU; the full suite
  never completed.

## Root cause

`TaskTitleRow` and `ChecklistItemRow` (commonMain) inject haptics unconditionally:

```kotlin
val haptic = if (LocalInspectionMode.current) null else koinInject<Haptic>()
```

The **only** `single<Haptic>` binding in the codebase lived in `PlatformModule.android.kt`.
The JVM `platformModule()` never bound it, so on desktop every task-detail/checklist
composition failed with `NoDefinitionFoundException: Haptic`.

The hang (not a clean red) follows from the test harness: a failed test captures a
`FailureBundle` (screenshot via `captureToImage`), which waits on the broken composition
and never settles — so the test class never finishes and writes no XML. Concurrent
classes stall behind it. Symptom in logs: one `Error was captured in composition`, then
an idle-looking JVM; `jstack` showed threads parked in `SkikoComposeUiTest.waitForIdle`.

Evidence: `desktopApp/build/test-results` empty for the hanging group; stack trace pinned
to `TaskTitleRowKt.TaskTitleRow` under `NoDefinitionFoundException`.

## Blast radius

Not limited to tests — the **production desktop app** was equally broken: opening any
task detail or checklist row crashed composition the same way. Android was unaffected
(binding exists). The regression shipped to `origin/main` with the celebration-overlay
MR, which evidently landed without a `:desktopApp:test` run.

## Fix

- `PlatformModule.jvm.kt`: `single<Haptic> { createHaptic(Unit) }` — the JVM actual is
  `NoOpHaptic`, matching the "haptics silently skipped on JVM" design.
- `desktopApp/.../test/helpers/TestPlatformModule.kt`: same binding for the harness graph.

## Prevention

- CI must run `:desktopApp:test` on every MR that touches `shared/` commonMain UI — the
  gap that let this through was a merge verified only by `jvmTest` + Android build.
- A Koin graph validation for the desktop shell graph (mirroring
  `KoinGraphValidationTest`) would fail fast on "commonMain component injects a port no
  platform module binds". Candidate follow-up: validate `testPlatformModule()` +
  `platformModule()` against the same set of `koinInject<T>()` requirements.

## Why did this pass review and tests

`jvmTest` compiles JVM sources but never *renders* a desktop composition, and Android
has the binding — so both the Android pipeline and the compile-only desktop pipeline
were green. The defect lived exactly in the seam those two pipelines don't cover.

## Related

- Flaky desktop test observed during verification (pre-existing, unrelated to this fix):
  `CreateTaskFlowTest.a_saved_task_without_a_due_date_appears_under_inbox_no_date`
  intermittently misses the `ContentDescription = 'Menu'` node (~1/3 under load).
