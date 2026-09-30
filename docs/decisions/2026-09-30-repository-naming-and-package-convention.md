---
title: MR-4 Repository naming and package convention
date: 2026-09-30
status: open
tags: [mr, repository, naming-convention]
---

# MR-4 — Repository naming and package convention

## Context

Repository implementation classes were inconsistently named (`Room*` prefix vs `*RepositoryImpl` suffix)
and inconsistently located (root of feature package vs `.data/` subpackage). The Konsist rule
`repository implementations are imported only from core di` enforced that `*RepositoryImpl` could only
be imported from `core/di`, but `Room*`-prefixed classes bypassed this rule entirely.

## Decision

**Naming:** All Room-backed repository implementations must use the `*RepositoryImpl` suffix.

**Package:** All repository implementations must live in the feature's `.data/` subpackage.
Interfaces remain in the feature root package.

```
feature/<name>/
  domain/           ← interfaces (ports), domain models
    port/
      <Name>Repository.kt    ← interface
  data/             ← implementations
    <Name>RepositoryImpl.kt   ← Room-backed impl
  presentation/     ← ViewModels, screens
```

**Konsist rule update:** The rule `repository implementations are imported only from core di`
was updated to also allow feature DI modules (`feature.agenda`, `feature.tasks`, `feature.notes`)
to import `*RepositoryImpl` from their own `.data/` subpackages.

## Changes

### Renamed classes
| Old | New |
|-----|-----|
| `RoomChecklistRepository` | `ChecklistRepositoryImpl` |
| `RoomReminderRepository` | `ReminderRepositoryImpl` |
| `RoomSavedAgendaViewsRepository` | `SavedAgendaViewsRepositoryImpl` |
| `RoomSavedSearchRepository` | `SavedSearchRepositoryImpl` |
| `RoomNotesRepository` | `NotesRepositoryImpl` |
| `InternalLinkRepositoryImpl` (file) | `InternalLinkRepositoryImpl` (moved to `feature.search.data`) |

### New packages
| Feature | New file |
|---------|----------|
| checklist | `feature.checklist.data.ChecklistRepositoryImpl` |
| reminders | `feature.reminders.data.ReminderRepositoryImpl` |
| notes | `feature.notes.data.NotesRepositoryImpl` |
| search | `feature.search.data.InternalLinkRepositoryImpl` |

### Deleted files (old locations)
- `feature/checklist/RoomChecklistRepository.kt`
- `feature/reminders/RoomReminderRepository.kt`
- `feature/search/InternalLinkRepositoryImpl.kt`

### DI module updates
- `NotesDiModule.kt` — updated import and binding for `NotesRepositoryImpl` and `InternalLinkRepositoryImpl`
- `CoreDiModule.kt` — updated import and binding for `ReminderRepositoryImpl`
- `TasksDiModule.kt` — updated imports and bindings for `ChecklistRepositoryImpl` and `SavedSearchRepositoryImpl`
- `AgendaDiModule.kt` — updated import and binding for `SavedAgendaViewsRepositoryImpl`

### Test updates
- `SavedAgendaViewsRepositoryImplTest.kt` — updated import and class reference
- `NotesRepositorySyncTest.kt` (renamed from `RoomNotesRepositorySyncTest.kt`) — updated import and class reference
- `FakeRepositories.kt` — updated KDoc reference to `SavedAgendaViewsRepositoryImpl`

## Consequences

- All `*RepositoryImpl` imports are now consistently either from `core/di` (Konsist enforced) or
  from feature `.data/` packages (allowed by updated rule)
- `Room*`-prefixed classes are no longer used — the Konsist rule now catches all production repo impls
- File names now match their class names (ktlint `Filename` rule satisfied)
