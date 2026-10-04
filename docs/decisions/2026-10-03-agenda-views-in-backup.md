# Add agenda_views to backup payload

**Date:** 2026-10-03
**Status:** decided

## Context

`BackupPayload` (`core/backup/BackupPayload.kt`) содержит: tasks, notes, projects, tags, attachments, taskTags, taskDependencies. Таблица `agenda_views` (сохранённые agenda views) **отсутствует** в payload.

При restore из backup все saved views теряются — данные пользователя пропадают без предупреждения.

Схожие user-data таблицы также отсутствуют: `task_reminders`, `project_reminders`, `checklist_items`, `tag_groups`, `project_tag_groups`, `saved_searches`, `time_entries`, `profiles`.

## Decision

1. Добавить `agenda_views` в `BackupPayload` (MR-1).
2. Bump `BackupFormat.SCHEMA_VERSION` с 2 до 3.
3. Добавить restore-тест в `BackupCodecContractTest` (agenda_views round-trip).
4. Остальные 8 таблиц — отдельный backlog-issue.

Изменения:

**`core/backup/BackupPayload.kt`:**
```kotlin
data class BackupPayload(
    val schemaVersion: Int,  // bump to 3
    val tasks: List<TaskEntity>,
    val notes: List<NoteEntity>,
    val projects: List<ProjectEntity>,
    val tags: List<TagEntity>,
    val attachments: List<AttachmentEntity>,
    val taskTags: List<TaskTagCrossRef>,
    val taskDependencies: List<TaskDependencyEntity>,
    // NEW:
    val agendaViews: List<AgendaViewEntity>,
)
```

**`core/backup/BackupDomain.kt`** — encode/decode для `agendaViews`.

**`core/backup/BackupCodec.kt`** — round-trip тест.

## Rationale

`saved views` — пользовательские данные, созданные явно (через BookmarkAdd). Их потеря при restore — data loss, не просто UX inconvenience. По приоритету: user data > derived data.

`profiles` и `time_entries` — тоже user data, но сложнее (FK constraints, time tracking integration). agenda_views — простой blob (sections_json), без внешних зависимостей.

## Consequences

- Пользователь восстанавливает saved views после backup/restore.
- `BackupFormat.SCHEMA_VERSION` 2→3: restore из старых backup'ов не включает agenda_views (graceful: поле отсутствует → default empty).
- 8 остальных таблиц — separate backlog-issue.

## Links

- `core/backup/BackupPayload.kt`
- `core/database/AgendaViewEntity.kt`
- `deferred-backlog.md#agenda-views-not-in-backup`
