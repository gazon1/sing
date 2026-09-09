---
title: "Instant Type Migration: kotlin.time.Instant → kotlinx.datetime.Instant"
status: deferred
date: 2026-09-08
deciders: Singularity Developer
---

## Context

The codebase mixes two `Instant` types:
- `kotlinx.datetime.Instant` — the project's datetime standard (used in Room entities, domain models, `kotlinx.datetime.Clock`)
- `kotlin.time.Instant` — deprecated stdlib type (imported transitively via `kotlinx.datetime.Clock`'s typealiases, or used directly)

This causes:
1. Deprecation warnings in `StatisticsScreen.kt` (typealias `Clock = kotlin.time.Clock`)
2. Deprecation warnings in `Clock.jvm.kt` (`typealias Instant = kotlin.time.Instant`)
3. Potential confusion for developers reading the codebase

## Decision

**Deferred** — the migration requires ~30 files to be updated and has significant scope creep risk.

## Scope Analysis

Files using `kotlin.time` namespace (direct or transitive):

| File | Usage |
|---|---|
| `core/platform/Clock.kt` | `expect object Clock { fun now(): kotlin.time.Instant }` — root of the issue |
| `core/platform/Clock.jvm.kt` | `typealias Instant = kotlin.time.Instant` + `Clock.now()` returns it |
| `core/platform/Clock.android.kt` | Similar to jvm |
| `feature/statistics/StatisticsScreen.kt` | Imports `kotlinx.datetime.Clock` (brings typealias) + preview data |
| `feature/pomodoro/PomodoroDomain.kt` | Uses `kotlin.time.Instant` for timers |
| `feature/ai/usage/UsageExtractor.kt` | Uses `kotlin.time` types |
| `core/observability/UsageRecorder.kt` | Uses `kotlin.time` types |
| `core/sync/SyncEngine.kt` | Uses `kotlin.time` types |
| `core/backup/BackupRepository.kt` | Uses `kotlin.time` types |
| `core/attachments/Attachment*.kt` | Uses `kotlin.time` types |
| `feature/tasks/*.kt` (multiple) | Uses `kotlin.time` for task timestamps |
| `feature/projects/*.kt` (multiple) | Uses `kotlin.time` |
| `feature/profile/Profile*.kt` | Uses `kotlin.time` |
| `feature/archive/ArchiveDomain.kt` | Uses `kotlin.time` |
| `test/fakes/FakeRepositories.kt` | Uses `kotlin.time` |
| `jvmTest/.../*Test.kt` (several) | Uses `kotlin.time` |

**Count: ~30 files** across commonMain, jvmMain, androidMain, and test source sets.

## Why Deferred

1. **Scope creep risk**: The plan estimates "5-10 files, mechanical replacement." Actual count is ~30.
2. **Anti-pattern violation**: Mixing this migration with UI work (PRs A-E) was specifically called out as an anti-pattern in the Phase 6 plan self-review.
3. **Low urgency**: Deprecation warnings don't break builds or runtime. The code works.
4. **Higher-value work**: Skills documentation and test improvements have better ROI.

## Migration Path (for when this is done)

The correct approach:

1. **Change `Clock.kt`** to return `kotlinx.datetime.Instant`:
   ```kotlin
   expect object Clock {
       fun now(): kotlinx.datetime.Instant
   }
   ```

2. **Rewrite `Clock.jvm.kt`** and **`Clock.android.kt`** actuals to return `kotlinx.datetime.Instant`.

3. **Mass-replace** `kotlin.time.Instant` → `kotlinx.datetime.Instant` and `kotlin.time.Clock` → `kotlinx.datetime.Clock` across all affected files.

4. **Verify** that Room entities (which store `kotlinx.datetime.Instant`) work correctly with the updated clock.

## Consequences

- Deprecation warnings in `StatisticsScreen.kt` and `Clock.jvm.kt` remain until migration is completed.
- Developers should prefer `kotlinx.datetime.Instant` in new code.
- `Clock.now()` should migrate to `kotlinx.datetime.Clock.System.now()` in a future PR.
