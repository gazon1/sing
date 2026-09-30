---
title: "Snapshot tests via Roborazzi for all detail screen sections"
date: 2026-09-08
tags: [testing, snapshot, roborazzi, quality]
superseded-by: 2026-09-30-roborazzi-not-built-superseded
status: superseded
---

## Context

The task UX rework (10 PRs) modifies every section of `TaskDetailScreen`. Manual UI testing cannot catch visual regressions across all state combinations (default, empty, overdue, completed, etc.). Industry standard is snapshot tests (Roborazzi on Android/JVM, Paparazzi as alternative).

## Decision

1. **Integrate Roborazzi** in `shared/build.gradle.kts` via plugin `io.github.takahirom.roborazzi`. Add version to `libs.versions.toml`.
2. **Snapshot tests** for all 8 UI components added/modified in the rework:
   - `TaskHeroSection` (3 states: default, completed, with description)
   - `TaskMetaChipsRow` (3 states: no-date, today, overdue, someday)
   - `TaskChecklistSection` (3 states: empty, partial, complete)
   - `TaskBottomActionBar` (4 states: default, with reminders, with attachments, pinned)
   - `SubtaskRow` (2 states: default, completed)
   - `ReminderListRow` (2 states: single, multiple)
   - `AttachmentThumbnail` (2 states: file, image)
   - `PickerSheetHost` (3 states: project picker, tag picker, priority picker)
3. **CI gate:** `./gradlew :shared:verifyRoborazzi` must pass. Any diff > 1% fails the build.
4. **Alternative:** If Roborazzi proves incompatible with KMP (Compose Multiplatform), fall back to **Paparazzi** (Square) — same `verifyRoborazzi` task replaced with `verifyPaparazzi`.

## Consequences

- Every future PR touching UI components must run snapshot tests and update baselines when changes are intentional.
- 3 preview functions per component (default, empty, edge case) — consistent with `2026-09-06-compose-previews` skill.
- Baseline images stored in `shared/src/commonTest/resources/roborazzi/`.

## Links

- `shared/build.gradle.kts` (Roborazzi plugin)
- `libs.versions.toml` (version pin)
- Skill: `singularity-todo-pure-formatters` (pure state for previews)
