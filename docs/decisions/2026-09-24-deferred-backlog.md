---
status: accepted
date: 2026-09-24
tags: [deferred, backlog, epic3]
epic: refactor/techdebt-epic2
---

# Deferred Backlog

Issues discovered during the tech debt audit that are **valid but deferred** — either because they require a larger feature context, a Phase 12 dependency, or are low-value changes. Each item includes the trigger condition for re-visiting.

## TaskMenuBuilder TODOs (~14 items)

**File:** `shared/src/commonMain/.../tasks/presentation/contextmenu/TaskMenuBuilder.kt`

All `// TODO: wire to XxxUseCase` items require the corresponding use case to be implemented. Trigger: when the feature team picks up MR-2 (task actions).

| Line | TODO | When to fix |
|---|---|---|
| 45, 51, 57 | Move to Today/Tomorrow/Next Monday | MR-2 task actions |
| 67 | Move to Inbox (projectId = null) | MR-2 |
| 70 | Enumerate existing projects dynamically | MR-2 |
| 102 | Add Label | MR-2 |
| 127 | Duplicate task | Feature backlog |
| 133 | Copy to project | Feature backlog |
| 139, 145 | Reorder | Feature backlog |
| 225 | Print task | Feature backlog |
| 231 | Archive | Already wired (dead code?) |
| 237 | Share | Feature backlog |

## Supabase Backend (Phase 12)

Trigger: when Supabase backend is implemented.

| File | Line | Description |
|---|---|---|
| `AuthRepository.kt` | ~90 | `TODO("Supabase: migrate anonymous to permanent")` — Phase 12 auth |
| `core/sync/SyncApi.kt` | — | Supabase API client — Phase 12 |

## Analytics SDK (Phase TBD)

| File | Line | Description |
|---|---|---|
| `CoreDiModule.kt` | ~154 | `RealAnalytics(binding)` — when RealAnalytics SDK is connected |

## Pending Task Saves (Phase 12 design)

**Trigger:** when concurrent task editing becomes a problem (telemetry).

- `PendingTaskSaves` — per-task write serialization to prevent concurrent edit clobbering
- `Channel<UNLIMITED>` in `CalendarSyncOrchestrator` — consider `CONFLATED` if memory growth observed

## Low-Value / Deferred

| Item | Reason | Mini-PR? |
|---|---|---|
| `kotlinx.datetime.Instant` migration (~30 files) | Low value, high risk of breaking Date/Time APIs | No |
| `anchorDate.monthNumber` (Int → `Month`) in `CalendarScreen.kt` | Breaking change, low urgency | Yes (mini-PR) |
| `Clock` typealias deprecation in `CoreDiModule.kt` | Type alias for backward compat, non-urgent | Yes (mini-PR) |
| `AndroidPomodoroTimerTest` skip-based tests | Migrate to `runTest + advanceTimeBy` | Yes (mini-PR) |
| `FakeSavedSearchRepository` + `FakeRemoteConfigRepository` | When these repos connect to tested VMs | Yes (mini-PR) |

## Status

All items above are **deferred, not forgotten**. Each has a clear trigger condition.
