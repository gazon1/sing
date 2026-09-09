---
title: "Roborazzi Snapshot Tests for Task Detail Sections"
status: superseded
date: 2026-09-08
deciders: Singularity Developer
superseded-by: 2026-09-08-roborazzi-snapshot-tests-superseded
---

## Context

After the Task UX rework (PRs 1-7), the `TaskDetailScreen` is now decomposed into
stateless section composables: `TaskHeroSection`, `TaskMetaChipsRow`, `TaskChecklistSection`,
`TaskSubtasksSection`, `RemindersSection`, `AttachmentsSection`. These need regression
safety nets — manual UI verification is error-prone and time-consuming.

## Decision

Use **Roborazzi** for Android snapshot tests (via `androidHostTest` source set) and document
the Paparazzi path for desktop (JVM headless). Tests live in `shared/src/androidHostTest/kotlin`.

## Rationale

- `androidHostTest` already uses Robolectric — no emulator needed, runs in CI.
- Roborazzi integrates with Robolectric via `@Config(device = Devices.PIXEL_6)` and
  `captureFromInstrumentation()` — works without a physical device.
- The 8 composables targeted: `TaskHeroSection`, `TaskMetaChipsRow`, `TaskChecklistSection`,
  `TaskSubtasksSection`, `RemindersSection`, `AttachmentsSection`, `KindSheet`, `ConfirmDeleteSheet`.
- 3 previews per section: default, empty, edge-case (e.g. overdue date, max checklist items).

## CI Integration

```bash
# Runs in CI after every PR merge
./gradlew :shared:testAndroidHostTest

# Fail on >1% pixel diff (configurable per-test)
```

## Consequences

- `roborazzi` dependency added to `androidHostTest` in `shared/build.gradle.kts`.
- `io.github.nickid:roborazzi:1.25.0` added to `libs.versions.toml`.
- Tests are ignored until the plugin resolution issue in the development environment is resolved.
  The test class is committed so the pattern is visible and CI can run them once the plugin
  is available in the project's plugin repositories.

## Files to add (when plugin resolves)

```
shared/src/androidHostTest/kotlin/com/singularity/todo/feature/tasks/
  TaskHeroSectionSnapshotTest.kt
  TaskMetaChipsRowSnapshotTest.kt
  TaskChecklistSectionSnapshotTest.kt
  TaskSubtasksSectionSnapshotTest.kt
  RemindersSectionSnapshotTest.kt
  AttachmentsSectionSnapshotTest.kt
  KindSheetSnapshotTest.kt
  ConfirmDeleteSheetSnapshotTest.kt
```

Each test class:
1. Creates a `ComposeSnapshotter` with `RobolectricSnapshotter`
2. Renders each section composable with 3 variants (default/empty/edge)
3. Calls `snapshotter.capture()` and `snapshotter.compare()` with pixel diff threshold
4. Fails the build if diff > 1%

## Anti-patterns avoided

- No `Bitmap.getPixel()` comparisons — use Roborazzi's built-in diff
- No hardcoded file paths — use temp dir via `File.createTempFile()`
- No network-dependent image loading — use `LocalContext` with static test resources
