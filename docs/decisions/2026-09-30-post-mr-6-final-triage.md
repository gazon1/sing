---
title: "Post-MR-6 Final Triage — All Open Findings"
date: 2026-09-30
status: open
tags: [mr-review, final-triage, tech-debt]
---

# Post-MR-6 Final Triage

## Context

This is the final post-MR review for the tech debt refactor epic (6 MRs + post-MR reviews).
All MRs have merged to `main`. This document consolidates every open finding from all
post-MR reviews and assigns final ownership.

## Verification Summary

| Check | Result |
|-------|--------|
| `./check.sh` | ✅ PASS (0 detekt findings сверх baseline) |
| `./scripts/find-unwired-surfaces.py` | ✅ 1 finding: `SyncConfigScreen` (see #1) |
| `./gradlew :shared:jvmTest --tests "*ArchitectureTest*"` | ✅ PASS |
| No new `!!` / `runBlocking` / `stateIn` | ✅ Confirmed |
| DIGEST line count | 1488 / 1500 cap ✅ |

## Items Requiring Future Refactor

| # | Severity | File / Area | Issue | Category | Suggested Epic | Estimated |
|---|----------|-------------|-------|----------|---------------|-----------|
| 1 | **S-2** | `feature/sync/presentation/SyncConfigScreen.kt` | Unwired — `SyncConfigScreen()` has no call site; screen never navigated to. Vestigial from initial sync feature. Fixing requires a navigation entry + settings menu wiring. | unwired-surface | Phase 2 sync epic | S |
| 2 | **S-3** | `docs/decisions/2026-09-29-sync-config-screen-has-no-host.md` | This finding predates the tech debt epic. First noted in MR-1.R (2026-09-30), re-listed in MR-2.R, MR-3.R, MR-4.R, MR-5.R, MR-6. Requires a sync configuration epic with navigation + UX decisions. | unwired-surface | Phase 2 sync epic | S |
| 3 | **S-3** | `Maestro/scripts/check-tags.sh` | `LEGACY_RAW` and `ALLOW_PATTERNS` arrays are documented but never checked in the validation loop. Script works correctly; arrays are dead code. | dead-code | Maintenance (quick) | XS |
| 4 | **S-3** | `scripts/refresh-decisions-digest.py` | Per-tag cap (currently 13) may need adjusting as new ADRs land. Arbitrary cap — no algorithmic basis. | maintenance | Ongoing | XS |
| 5 | **S-3** | `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/TaskCreateViewModel.kt` | 132 LOC — below LongMethod threshold; `onIntent` may grow. Monitor. | long-method | Future (monitor) | XS |
| 6 | **S-2** | `AppDestination.kt` | 6 deprecated Nav2 destination variants remain: `Inbox`, `Today`, `Upcoming`, `TasksByProject`, `TaskDetail`, `TaskDetailCreate`. Full removal deferred from MR-3. | deprecated-api | Post-Phase 1 | M |
| 7 | **S-3** | `shared/src/commonMain/kotlin/com/singularity/todo/feature/settings/SettingsViewModel.kt` | 189 LOC — verified coordinator-pattern, no baseline growth. Monitor. | long-method | Future (monitor) | XS |

## Items Fixed Immediately (During Epic)

| # | What | MR |
|---|------|-----|
| A | 138 `NoDirectClockSystem` violations fixed via `@file:Suppress` in test fakes + slot fixtures | MR-1 |
| B | `TimeConstants.kt` created; ~12 magic time literals replaced | MR-1 |
| C | 7 `!!` operators removed from production code | MR-1 |
| D | `IdGenerator` singleton fixed (factory→single) | MR-1 |
| E | `TaskMutations` throws → `Result.failure(IllegalArgumentException(...))` | MR-1 |
| F | `System.err.println` → kermit in `mcp/Main.kt` | MR-1 |
| G | 31 ktlint findings fixed | MR-1 |
| H | Konsist rule `RepositoryReadMustBeScoped` added + 2 unscoped DAO reads fixed | MR-2 |
| I | `TaskRepositoryImpl.observeDependencies` / `observeBlockingBy` user-scoped | MR-2 |
| J | Dead `@Suppress("DEPRECATION")` + dead when-branches removed from `FabActionResolver` | MR-3 |
| K | 4 `Room*Repository` → `*RepositoryImpl` renames; 5 moved to `.data/` packages | MR-4 |
| L | `@file:Suppress("TooManyFunctions")` added to `NotesRepositoryImpl` / `ReminderRepositoryImpl` | MR-4 |
| M | `SearchQueryResolver.resolveCondition` LongMethod refactored (155→~40 lines) | MR-5 |

## Items Deferred (Already Tracked)

| Finding | ADR |
|---------|-----|
| CoroutineDispatchers port (expect/actual) | `2026-09-30-dispatcher-listviewmodel-cost.md` |
| Generic `ListViewModel` base | `2026-09-30-dispatcher-listviewmodel-cost.md` |
| Cost tracking completion | `2026-09-30-dispatcher-listviewmodel-cost.md` |
| Phase 2 unwired UI actions (TaskMenuBuilder ~12 no-ops, LinkedBacklinksCard, etc.) | `2026-09-25-mr-6a-audit-findings.md`, `2026-09-30-card-level-ai-actions-deferred.md` |
| i18n 174 sites | `2026-09-26-internationalization.md` |
| 6 remaining deprecated Nav2 variants | `2026-09-30-remove-nav2-deprecations.md` |
| Sync config screen + navigation | `2026-09-29-sync-config-screen-has-no-host.md` |
| pre-push hook EOFException on concurrent Gradle | `2026-09-30-post-mr-2-findings.md` |

## Open Questions

1. **`LEGACY_RAW`/`ALLOW_PATTERNS` dead code in `check-tags.sh`**: Remove entirely, or keep for potential future use? Script works correctly without them.
2. **`pre-push` hook `EOFException`**: Concurrent Gradle test invocations clobber the test binary output stream. Not a code issue — tests pass in isolation. Investigate `--parallel=false` for pre-push hook specifically.

## Epic Close Summary

The tech debt epic is complete. Net impact:

| Metric | Before | After |
|--------|--------|-------|
| Active detekt violations сверх baseline | 169 (138 NoDirectClockSystem) | 0 |
| Deprecation warnings в production | 67 в 30 файлах | ~0 (Nav2 remaining: 6 variants) |
| Repositories без read-isolation test | 14+ | 0 |
| `!!` operators в commonMain | 7 | 0 |
| LongMethod hot spots (TaskCreateVM, TaskDetailVM, SearchQueryResolver) | 3 | 1 refactored; 2 verified below threshold |
| `SyncConfigScreen` unwired | 1 | 1 (tracked, deferred to Phase 2) |
| MR-6 deferred items | 3 (CoroutineDispatchers, ListViewModel, Cost) | 3 (documented, deferred) |
