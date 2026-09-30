---
title: Post-MR-5 findings — God-VM Decomposition
date: 2026-09-30
status: open
tags: [mr-post-review, vm, long-method, tech-debt]
---

# Post-MR-5 findings — God-VM Decomposition

## Context

MR-5 refactored `SearchQueryResolver.resolveCondition` (155-line when block) into 6 extracted private methods. Initial plan also scoped SettingsViewModel split and TaskDetailViewModel LongMethod refactor, but inspection revealed both already follow coordinator/slot patterns.

## Items fixed immediately

- **SearchQueryResolver LongMethod refactored**: `resolveCondition` 155 → ~40 lines. Six branch methods extracted:
  `handleHasStatus`, `handleHasTag`, `handleHasAllTags`, `handleInProject`, `handleDue`, `handleScheduled`.

## Items requiring future refactor

| # | File | Issue | Category | Severity | Suggested MR | Estimated effort |
|---|------|-------|----------|----------|-------------|-----------------|
| 1 | `feature/sync/presentation/SyncConfigScreen.kt` | Unwired — `SyncConfigScreen()` has no call site | unwired-surface | medium | MR-6 | S |
| 2 | `feature/settings/SettingsViewModel.kt` | 189 LOC — verify no detekt baseline growth; already coordinator-pattern | long-method | low | MR-6 (monitor) | XS |
| 3 | `feature/tasks/presentation/viewmodel/TaskCreateViewModel.kt` | 132 LOC — below LongMethod threshold but `onIntent` may grow | long-method | low | Future (monitor) | XS |

## Items deferred (already tracked)

| ADR | Issue |
|-----|-------|
| `2026-09-30-god-vm-decomposition.md` | This MR |
| `2026-09-30-post-mr-4-findings.md` | `SyncConfigScreen` unwired |
| `2026-09-30-dispatcher-listviewmodel-cost.md` (planned for MR-6) | CoroutineDispatchers port, ListViewModel base, cost tracking |

## Verification

- `./check.sh` — **PASS** (0 detekt findings)
- `./scripts/find-unwired-surfaces.py` — **1 finding**: `SyncConfigScreen` (pre-existing, tracked above)
- Architecture tests — **PASS**
- No new `!!`, no new `runBlocking`, no new `stateIn`
