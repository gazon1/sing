---
title: Phase 4 Post-Move Cleanup Findings
date: 2026-10-01
status: accepted
id: 2026-10-01-phase4-cleanup-findings
description: >
profile: ai-agent
---

## Context

After moving 7 repository interfaces to `domain/port/`, an audit revealed several
categories of issues: naming collisions, misplaced interfaces, non-conforming impl
suffixes, dead suppressions, and architecture violations. This ADR records what was
fixed inline vs. what is deferred.

## Fixed Inline (Phase 4 cleanup commit)

### Critical: Duplicate class name `RemoteConfigRepositoryImpl`

Two distinct classes with identical simple name in different packages:
- `core.config.RemoteConfigRepositoryImpl` — Room + network cache, implements `RemoteConfigPort`
- `core.sync.RemoteConfigRepositoryImpl` — DAO-backed, implements `RemoteConfigRepository`

**Fix:** Renamed `core.config.RemoteConfigRepositoryImpl` → `RemoteConfigCacheRepositoryImpl`.

### Critical: `ArchiveViewModel` referencing impl directly

`ArchiveViewModel` took `TaskDaoArchiveRepositoryImpl` as a constructor param,
violating Konsist rule "repository implementations are imported only from di modules."

**Fix:** Changed to depend on `ArchiveRepository` interface (from `domain/port/`).

### Interface/impl placement violations

| Item | Before | After |
|---|---|---|
| `ProjectRemindersRepository` interface | `feature/reminders/ProjectRemindersRepository.kt` (root) | `feature/reminders/domain/port/` |
| `ProjectRemindersRepositoryImpl` | same file as interface | `feature/reminders/data/` |
| `DefaultAgendaViewSettingsRepository` interface | `feature/agenda/DefaultAgendaViewSettingsRepository.kt` (root) | `feature/agenda/domain/port/` |
| `ReminderFormatter` | `feature/tasks/domain/port/ReminderFormatter.kt` (not a port) | `feature/tasks/domain/util/` |

### Non-conforming impl suffixes (Konsist `REPOSITORY_IMPL_SUFFIXES`)

Renamed to match the allowlist:
- `TaskDaoArchiveRepository` → `TaskDaoArchiveRepositoryImpl`
- `CalendarSyncSettingsRepository` → `CalendarSyncSettingsRepositoryImpl`
- `NoopCalendarSyncRepository` → `NoopCalendarSyncRepositoryImpl`

### Dead `@file:Suppress("NoDirectClockSystem")`

Removed from files that no longer use `Clock.System`:
- `RoomUsageRecorder.kt`
- `StatisticsViewModel.kt`
- `NoteEditorScreen.kt`

---

## Deferred (Tech Debt Backlog)

### 1. Remaining `@file:Suppress("NoDirectClockSystem")` — over-broad

These files have the suppression but it is over-broad (silences the whole file).
The actual `Clock.System` usage is legitimate in some cases:

| File | Issue |
|---|---|
| `AgendaDeps.kt:17` | `Clock.System` as default param — suppression over-broad |
| `SavedAgendaViewModel.kt:27` | same |
| `CalendarDeps.kt:22` | same |
| `SavedSearchRepositoryImpl.kt:22` | same pattern |
| `SavedAgendaViewsRepositoryImpl.kt:23` | same pattern |
| `OAuthTokenRefresh.kt:31,45` | same pattern |

**Action:** Replace `@file:Suppress` with per-parameter `@Suppress("NoDirectClockSystem")`
on the specific default-param lines. Phase 1 R26 work covered the removal target;
this is the cleanup of the remaining over-broad suppressions.

### 2. Active `Clock.System` usage in non-DI contexts

These files use `Clock.System.now()` directly in production code (not as defaults):

| File | Location | Severity |
|---|---|---|
| `CalendarDiModule.kt:32` | inside `todayFlow` factory | Medium |
| `RemoteConfigRepositoryImpl.kt:69` | inside `refresh()` | Medium |
| `ProfileSwitcherViewModel.kt:71` | inside VM | High |
| `SavedAgendaListViewModel.kt:97` | inside VM | High |
| `NoteEditor.kt:69,96` | inside VM | High |
| `SearchScreen.kt:251,252,259,260` | Compose screen | High |
| `NotePreviewScreen.kt:431,467,468` | Compose screen | High |
| `FileLogWriter.kt:69` | core log writer | Medium |

**Action:** Inject `Clock` into the VM/screen constructors; for screens, route through
a UseCase. Most are straightforward `Clock` param additions.

### 3. ViewModels >200 LOC without slot tests

Pattern established for `tasks` feature (8 slot tests) should be applied to:

| ViewModel | LOC | Priority |
|---|---|---|
| `ProjectDetailViewModel` | 425 | High |
| `SearchViewModel` | 350 | Medium |
| `SavedAgendaViewModel` | 311 | Medium |
| `NotesListViewModel` | 257 | Medium |
| `BackupViewModel` | 211 | Low |

### 4. Large files (>500 LOC)

| File | LOC | Recommendation |
|---|---|---|
| `FakeRepositories.kt` | 1973 | Test only — acceptable |
| `FakeAppDatabase.kt` | 1345 | Test only — acceptable |
| `Daos.kt` | 910 | Consider splitting by entity group |
| `NotesListScreen.kt` | 632 | Extract swipe-action components |
| `ProjectDetailContent.kt` | 593 | Already suppressed at baseline |

### 5. Stub Supabase implementations (TODO markers)

| File | TODO count | Priority |
|---|---|---|
| `SyncApi.kt` | 6 | High — sync blocked |
| `AuthRepository.kt` | 3 | High — auth blocked |
| `CoreDiModule.kt:224` | 1 | Low — analytics stub |

### 6. `ReminderFormatter` misnamed — not actually a port

The file `feature/tasks/domain/port/ReminderFormatter.kt` contains a plain function
`dueInstant()`, not an interface or port. Moved to `domain/util/`. No further action needed.

---

## Links

- Phase 4 ADR: `2026-10-01-cluster-9-repository-package-moves.md`
- Epic 2 plan: `2026-10-01-remaining-tech-debt.md`
- Phase 1 R26: `2026-10-01-r26-kotlin-time-consolidation.md`
