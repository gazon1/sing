---
title: "Superseded: Roborazzi Snapshot Tests for Task Detail Sections"
status: superseded
date: 2026-09-08
superseded-by: 2026-09-08-roborazzi-snapshot-tests
deciders: Singularity Developer
---

## Status

This ADR is **superseded** by the decision to defer snapshot testing to a future effort.

## What Was Investigated

1. **Library resolution**: The `io.github.nickid:roborazzi` group used in the original ADR does not exist on Maven Central.
   The correct group is `io.github.takahirom.roborazzi` (e.g., `io.github.takahirom.roborazzi:roborazzi:1.74.0`).
   The Gradle plugin is `io.github.takahirom.roborazzi` available from Gradle Plugin Portal.

2. **Compose API**: The Roborazzi Compose integration API in version 1.74.0 requires further investigation.
   The `captureToBitmap()` extension used in initial test attempts is not available in the standard library artifact.
   The `roborazzi-compose` submodule may be required.

## Why Deferred

- The primary blocker is the Compose API uncertainty — without a working minimal test, CI baselines cannot be established.
- The existing Robolectric widget tests (`AppNavigatorTest`, etc.) provide reasonable coverage for regression detection.
- The sections being targeted (`TaskHeroSection`, `TaskSubtasksSection`, etc.) are already tested indirectly through
  ViewModel state tests.
- The mechanical cleanup (PRs A, B, C) is higher value than snapshot tests at this point.

## What Remains

- `libs.versions.toml` now has the correct Roborazzi coordinates (`io.github.takahirom.roborazzi:roborazzi:1.74.0`).
- `shared/build.gradle.kts` has the plugin and dependency commented out, ready to uncomment.
- A single test file `TaskHeroSectionSnapshotTest.kt` was created and deleted during investigation.

## When to Revisit

When a developer has bandwidth to:
1. Establish a minimal working test using `captureFromInstrumentation()` or the correct Compose capture API
2. Verify the `roborazzi-compose` submodule works with the current Robolectric + Compose setup
3. Set up CI baselines

## Files Modified During Investigation

- `gradle/libs.versions.toml` — corrected `roborazzi` version to `1.74.0` and module to `io.github.takahirom.roborazzi:roborazzi`
- `shared/build.gradle.kts` — added then reverted `alias(libs.plugins.roborazzi)` and `implementation(libs.roborazzi)`
