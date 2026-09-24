# Agenda

Calendar-based task scheduling. Provides daily/weekly schedule views, saved agenda configurations, and per-profile view settings.

## Structure

```
agenda/
├── AgendaDiModule.kt   Koin DI bindings
├── DefaultAgendaViewSettingsRepository.kt
├── presentation/
│   ├── screen/   AgendaScreen
│   ├── viewmodel/ AgendaViewModel, SavedAgendaListViewModel, SavedAgendaViewModel
│   └── components/ Date header, task list sections
├── domain/     Agenda domain logic
├── data/       RoomSavedAgendaViewsRepository (implements SavedAgendaViewsRepository)
└── port/       AgendaSelector, view settings ports
```

## Key entry points

| What | Where |
|---|---|
| Main agenda | `AgendaScreen` + `AgendaViewModel` |
| Saved configs | `SavedAgendaListViewModel`, `SavedAgendaViewModel` |
| Repository | `SavedAgendaViewsRepository` (interface), `RoomSavedAgendaViewsRepository` |
| Nav graph | `AgendaNavGraph`, `agendaEntryProvider` |

## Relevant ADRs

- `docs/decisions/2026-09-05-koog-both-platforms.md` — platform split
- `docs/decisions/2026-09-21-profile-repository-migration.md`
