---
title: "Task time tracking: estimate, time_entries, timer, Pomodoro"
date: 2026-10-02
status: accepted
tags: [timetracking, tasks, database]
---

## Context

Lotti tracks time per-task with a timer that starts from `startedAt` (not accumulated ticks),
and records work-phase Pomodoro sessions. Audio recordings are tracked as `Recording` kind
and excluded from work-time totals. Estimates are nullable (no estimate ≠ 0 minutes).

This ADR covers MR-1 (data layer) and MR-2 (timer, Pomodoro, manual entry).

## Decision

### `Task.estimateMinutes: Int?`

Nullable integer. `null` means "no estimate set" — distinct from 0, which means "estimated
at zero minutes". Stored as `INTEGER?` in both the entity and the domain model.

`estimate_minutes` column added to `tasks` table. `row_version` increments on any task mutation
(not just estimate changes).

### `TimeEntryEntity`

```
@Entity(tableName = "time_entries",
        indices = [Index("task_id"), Index("started_at")])
data class TimeEntryEntity(
    id: String,
    task_id: String,
    user_id: String,           -- ownership
    started_at: Long,          -- epoch millis (NOT Julian day)
    ended_at: Long?,           -- null = entry is running
    kind: String,              -- Work | Recording
    source: String,            -- Manual | Timer | Pomodoro | AiProposal
    note: String?,
    created_at: Long,
    updated_at: Long,
    deleted_at: Long?,         -- soft delete
    sync: SyncColumns,
)
```

**All columns use `@ColumnInfo(name = "snake_case")`**. Room 3 does not tolerate camelCase
column names on entity fields without explicit `@ColumnInfo`.

**Epoch millis for all timestamps** (not Julian day). ADR 0025 Lotti §2 documents that
`julianday()` on integer columns returns NULL and silently drops all rows. We store epoch
millis and compute duration by subtracting the `started_at` and `ended_at` columns directly.
No SQLite date functions are used on these columns.

### `kind = Recording`

Audio recordings (Lotti's primary feature) are tracked as `TimeEntryKind.Recording`. The
`TimeBucketing.unionDuration` function filters `kind = Work` when summing time toward
task progress. This makes the "audio ≠ work" rule a structural property of the data model
rather than a heuristic, so MR-3's bucketing cannot accidentally count recordings.

### Timer counts from `startedAt`

`TaskTimeSlot` (plain class, not a Koin component) tracks open entries. The UI displays
`clock.now() - startedAt` — not accumulated ticks. This means the displayed elapsed time
is correct after process death, after device sleep, and across rotation.

Only one open entry per user at a time — enforced in `TimeTrackingRepository.startEntry`,
not in the UI layer.

### Pomodoro writes only the work phase

`AndroidPomodoroTimer` and `JvmPomodoroTimer` implement `PomodoroSessionSink` (commonMain
port). The sink is called with `onWorkPhaseCompleted(taskId, startedAt, endedAt)` — only
the work phase, never the break. `kind = Work`, `source = Pomodoro`.

The sink is called **before** the timer resets its internal state, while `taskId` is still
available in mutable state.

### Migrations

- `Migration25To26`: adds `estimate_minutes` to `tasks`, increments `SCHEMA_VERSION`
- `Migration26To27`: creates `time_entries` table with all indices; both use `AutoMigrationSpec`

`AutoMigration(from = 26, to = 27, spec = Migration26To27::class)` in `AppDatabase.kt`.

## Rationale

Storing `startedAt` as the source of truth (instead of accumulated milliseconds) is the key
design decision. The alternative — storing elapsed milliseconds and incrementing on each
tick — accumulates error from timer drift, process death, and system sleep. Counting from
`startedAt` means the current elapsed is always `now() - startedAt`, which is accurate
regardless of how many times the process was killed.

Epoch millis (not Julian day) was chosen because the existing `tasks` and `notes` tables
already use epoch millis via `SyncColumns`, and mixing Julian day into the same column
types would create ambiguity in future queries that join time entries with tasks.

`source = Pomodoro` is recorded so that time entries from Pomodoro can be attributed
correctly in Insights. Without it, Pomodoro time would be indistinguishable from manual
time and would be misattributed in the per-source breakdown.

## Consequences

- `TimeTrackingRepository` lives in `feature/timetracking.domain` — the domain layer, per
  the canonical CRUD pattern. The data-layer implementation is in `feature/timetracking.data`.
- `TimeEntryId` is a `@JvmInline value class` wrapping `String`, matching the pattern for
  all other IDs in the project.
- Cross-device sync of time entries is deferred. Time tracking is local-only for now;
  adding a sync doc-type for `time_entry` is a future step.
- JVM Desktop has no native Pomodoro notification system. `JvmPomodoroTimer` is a stub
  that calls `PomodoroSessionSink` on work-phase completion but produces no desktop
  notifications. This is acceptable for the MVP.

## Links

- `feature/timetracking/domain/TimeTrackingRepository.kt` — port interface
- `feature/timetracking/data/TimeTrackingRepositoryImpl.kt` — Room impl
- `feature/timetracking/domain/logic/TimeBucketing.kt` — `unionDuration` with `kind = Work` filter
- `core/platform/PomodoroSessionSink.kt` — commonMain port
- `androidMain/.../AndroidPomodoroTimer.kt` — Android impl
- `jvmMain/.../JvmPomodoroTimer.kt` — JVM stub impl
- ADR 0025 Lotti (design reference)
