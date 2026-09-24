---
title: "Task Schema v16 — Rich Dates & Styling (MR-2a)"
status: accepted
---

# Task Schema v16 — Rich Dates & Styling (MR-2a)

## Context

MR-2a of the 6-MR calendar modernization plan requires extending the Task schema with
rich-date fields (start/end date+time) and per-task styling (accent color, emoji).
These fields are needed so that:

- Calendar UI can display task time ranges (start → end) and visual styling
- Task creation/edit screens can set a start date and an explicit end date
- Task chips on the calendar can show emoji and accent color

The schema was v15. MR-2a covers only the schema migration — no UI changes.

## Decision

### Schema change: v15 → v16

Six new nullable columns added to `TaskEntity`:

| Column | Type | Nullable | Description |
|---|---|---|---|
| `start_date` | TEXT | yes | ISO LocalDate — when the task becomes active |
| `start_time` | TEXT | yes | ISO "HH:mm:ss" — start time |
| `end_date` | TEXT | yes | ISO LocalDate — deadline / end of active window |
| `end_time` | TEXT | yes | ISO "HH:mm:ss" |
| `accent_color` | INTEGER | yes | ARGB color value (Long), null = use default |
| `emoji` | TEXT | yes | Task-level emoji, null = none |

**Null semantics**: all six columns are nullable with no default. Existing rows
read back with null for all six. This is safe for auto-migration.

### Domain model changes

`Task` data class receives the same six nullable fields:
```kotlin
val startDate: kotlinx.datetime.LocalDate? = null
val startTime: kotlinx.datetime.LocalTime? = null
val endDate: kotlinx.datetime.LocalDate? = null
val endTime: kotlinx.datetime.LocalTime? = null
val accentColor: Long? = null
val emoji: String? = null
```

`CreateTaskInput` also receives the same six fields for task creation.

### Mappers

`TaskEntity.toTask()` in `Mappers.kt` maps all six new columns.
`Task.toEntity()` in `TaskRepositoryImpl.kt` maps all six new columns.

### Backup DTOs

`TaskDto` in `BackupDtos.kt` receives the same six fields.
Both `TaskEntity.toDto()` and `TaskDto.toEntity()` updated accordingly.
This ensures backup/restore roundtrip preserves the new fields.

### Migration

`Migration15To16 : AutoMigrationSpec` — empty class, no `migrate()` override.
Room KSP auto-infers the schema diff (15.json → 16.json) from the entity changes.
AutoMigration registered in `AppDatabase`:
```kotlin
AutoMigration(from = 15, to = 16, spec = Migration15To16::class)
```

KSP auto-generated `schemas/com.singularity.todo.core.database.AppDatabase/16.json`.

## Rationale

- All columns nullable: backward-compatible with existing rows (null = no start/end/style)
- Pure addition: no existing columns altered or removed
- `accentColor` uses `Long` (ARGB) to match Android `Color` representation
- `emoji` uses `String?` — standard emoji codepoint or flag sequence
- AutoMigration: no manual SQL, KSP handles the diff

## Consequences

- `TaskEntity` is now 6 columns wider — acceptable storage cost
- Backup/restore roundtrip must include new fields (done via `TaskDto` update)
- Tests that construct `TaskEntity` directly must include all 6 new nullable parameters
- MR-2b (UI) will wire these fields into task create/edit screens

## Links

- MR-2a branch: `feature/calendar-mr1-kizitonwose` (same branch, stacked commits)
- MR-2b: Task UI for new fields + Calendar mapper updates
- Schema file: `shared/schemas/com.singularity.todo.core.database.AppDatabase/16.json`
