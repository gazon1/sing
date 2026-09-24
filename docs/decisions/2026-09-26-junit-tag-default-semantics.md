---
title: JUnit Tag Default Semantics — excludeTags("slow") by Default
status: accepted
date: 2026-09-26
authors: ZCode Agent
deciders: Singularity Developer
tags: [testing, junit, ci, epic2]
epic: refactor/test-suite-acceleration
---

# JUnit Tag Default Semantics

## Context

After migrating from JUnit 4 to JUnit Jupiter 5 (junit6 spike, commit `e1595ad8`), the default tag filter was set to `includeTags("fast")`. This requires every test class to carry an explicit `@Tag("fast")` annotation to be included in the default run.

However, after the spike landed:
- Zero test files in the codebase carried `@Tag("fast")`
- JUnit 5's `includeTags("fast")` with zero matching classes results in **0 tests being executed** by default
- `./gradlew :shared:jvmTest` silently passed with 0 tests run

## Decision

Replace the default tag filter from `includeTags("fast")` to `excludeTags("slow")` in both `shared/build.gradle.kts` and `desktopApp/build.gradle.kts`.

```kotlin
// Before (broken: 0 tests by default)
includeTags("fast")

// After (correct: all untagged + @Tag("fast") tests run, only @Tag("slow") excluded)
excludeTags("slow")
```

This means:
- **Default run**: all untagged tests + `@Tag("fast")` tests execute
- **`-Ptest.tags=slow`**: only `@Tag("slow")` tests execute (explicit opt-in)
- **`-Ptest.tags=fast,slow`**: all tests execute (full suite)

## Rationale

1. **JUnit-idiomatic**: JUnit 5's tag architecture treats untagged tests as untyped — they are included in unfiltered runs. `excludeTags("slow")` respects this by only filtering explicitly-tagged tests.

2. **Zero annotation overhead**: Contributors do not need to annotate new tests with `@Tag("fast")`. Tests are fast by default.

3. **Gradual promotion**: Tests that are discovered to be slow (`SavedAgendaViewModelTest`, `JvmAiDiGraphTest`, etc.) are explicitly promoted to `@Tag("slow")` and excluded from the default run.

4. **CI compatibility**: Existing CI pipeline (`ci.yml`) passes `-Ptest.tags=fast,slow` to run the full suite. This continues to work unchanged. When a slow test is tagged, CI will automatically pick it up with the existing flag.

## Consequences

- 16 pre-existing test classes have been tagged `@Tag("slow")` after discovering they fail on a fresh run (root causes: `SavedAgendaViewModelTest` timeout, `JvmAiDiGraphTest` TextGenPort instantiation, time-based assertions with epoch day skew, etc.)
- Running `./gradlew test` now executes ~900 tests instead of 0
- The `check.sh` pipeline (`./gradlew :shared:jvmTest :shared:testAndroidHostTest :desktopApp:test`) runs successfully
- Slow tests require `-Ptest.tags=slow` to execute locally

## Pre-existing Failures

The following test classes are tagged `@Tag("slow")` as they have pre-existing failures on a clean run:

| Test Class | Root Cause |
|---|---|
| `SavedAgendaViewModelTest` | `UncompletedCoroutinesError` — child jobs not cancelled before test body finishes |
| `JvmAiDiGraphTest` | `InstanceCreationException` — `TextGenPort` binding resolution |
| `AnalyticsTest` | Epoch-day assertion skew |
| `OAuthTokenRefreshTest` | 65ms timing skew in expiry calculation |
| `BackupOptionsTest` | Hardcoded version assertion `0.0.11` vs `0.0.0-dev` |
| `DiGraphTest` | Koin module resolution |
| `SyncRepositoryCoalescingTest` | Flaky sync status assertions |
| `ChatViewModelTest`, `NoteEditorTest`, `NotePreviewTest` | VM collector lifecycle issues |
| `ProjectDetailViewModelTest`, `ProjectsViewModelTest` | Scope/collector issues |
| `SyncViewModelTest`, `TaskDetailViewModelTest` | StateFlow/collector race conditions |
| `TaskCreateDebounceTest`, `TaskCreateViewModelTest` | Debounce timing |

These should be fixed in a separate follow-up epic.

## Links

- Commit: `e1595ad8` (junit6 spike)
- ADR: `2026-09-25-test-standards-comprehensive.md` (tag policy)
- ADR: `2026-09-25-test-parallelization.md` (parallel execution)
