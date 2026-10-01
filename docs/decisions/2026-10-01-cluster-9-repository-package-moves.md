---
title: Cluster 9 — Repository Package Moves
date: 2026-10-01
status: accepted
id: 2026-10-01-cluster-9-repository-package-moves
description: >
profile: ai-agent
---

## Context

Epic 2 (Tech Debt Refactor) requires consistent repository naming and package
convention across the codebase. Previous phases established the pattern
(`feature/<x>/domain/port/<Name>Repository`); Phase 4 applies it to the
remaining 7 repositories that still used non-canonical locations.

## Decision

Move the following repository interfaces to `feature/<x>/domain/port/`:

| Repository | From | To |
|---|---|---|
| `NotesRepository` | `feature/notes/NotesRepository.kt` | `feature/notes/domain/port/NotesRepository.kt` |
| `InternalLinkRepository` | `feature/search/InternalLinkRepository.kt` | `feature/search/domain/port/InternalLinkRepository.kt` |
| `ProfileRepository` | `feature/profile/ProfileRepository.kt` | `feature/profile/domain/port/ProfileRepository.kt` |
| `ReminderRepository` | `feature/reminders/ReminderRepository.kt` | `feature/reminders/domain/port/ReminderRepository.kt` |
| `ChecklistRepository` | `feature/checklist/ChecklistRepository.kt` | `feature/checklist/domain/port/ChecklistRepository.kt` |
| `CalendarSyncRepository` | `feature/calendar_sync/domain/repository/` | `feature/calendar_sync/domain/port/` |
| `ArchiveRepository` | `feature/archive/ArchiveRepository.kt` | `feature/archive/domain/port/ArchiveRepository.kt` (new interface, impl renamed) |

### ArchiveRepository special case

`ArchiveRepository` was a concrete Room DAO-based class, not an interface.
Created `ArchiveRepository` interface in `domain/port/`, moved the implementation
to `feature/archive/data/TaskDaoArchiveRepositoryImpl.kt` (per the
`REPOSITORY_IMPL_SUFFIXES` Konsist rule).

## Miscount discovered

The plan mentioned "9 repositories" but only 7 were in scope after excluding
`TaskRepository` (already canonical) and `SavedSearchRepository` (already
canonical). The actual count was 7.

## Import ordering surprises

`domain.port.X` imports sort before `NotesUiEvent` / `NotesUiState` because
`'d' < 'N' < 'S'` in lexicographic comparison of the first differing segment.
This caused 9 `ImportOrdering` detekt violations that detekt auto-fix resolved.
Manual verification confirmed `domain.port.NotesRepository` now correctly sits
between `NoteId` and `NotesUiEvent` imports.

## Consequences

- All 7 repository interfaces now live in `feature/<x>/domain/port/`
- 0 interfaces in `data/` directories
- All callers updated with new import paths (42 files across main + test sources)
- Konsist `REPOSITORY_IMPL_SUFFIXES` rule satisfied
- `python3 scripts/find-unwired-surfaces.py` → 0 unwired surfaces

## Links

- Epic 2 plan: `docs/decisions/2026-10-01-remaining-tech-debt.md`
- Repository architecture convention: `docs/decisions/2026-09-30-repository-naming-and-package-convention.md`
