---
status: accepted
---

# System Calendar Provider sync (one-way)

## Context

Tasks with due dates are invisible outside the app. Users want their task reminders to appear in their existing system calendar (Google Calendar, Samsung Calendar, etc.) so they get phone notifications, calendar widgets, and cross-device sync for free.

The existing `ReminderRepository` fires one-shot alarms but has no awareness of calendar providers.

## Decision

Implement a new feature module `feature/calendar_sync/` with **one-way sync** from Task → Android system calendar. The sync is opt-in, per-profile, and runs via WorkManager.

### Architecture

```
feature/calendar_sync/
  domain/
    model/
      CalendarSyncEvent      — canonical task→calendar event (deep-link, RRULE)
      RecurrenceRule         — sealed: Daily, Weekly, Monthly, Yearly, Custom
      SyncPlan               — sealed: Insert, Update, Delete, NoOp
      CalendarSyncStatus     — sealed: Disabled, Idle, Syncing, Failed
    logic/
      CalendarEventMapper    — Task + Reminder? → CalendarSyncEvent (pure)
      RecurrenceRuleMapper  — recurringPattern string → RecurrenceRule (pure)
      RruleGenerator         — RecurrenceRule → RFC 5545 RRULE string (pure)
      SyncDiffMerge          — (existing eventIds, desired events) → List<SyncPlan> (pure)
    port/
      CalendarProviderPort   — interface for ContentResolver operations
    repository/
      CalendarSyncRepository — interface: enabled, targetCalendarId, lastSyncedAt, status
  data/
    CalendarSyncTaskMapDao   — Room DAO: task_id → calendar_event_id mapping
    CalendarSyncSettingsRepository — DataStore-backed CalendarSyncRepository
    AndroidCalendarProvider  — ContentResolver implementation of CalendarProviderPort
    NoopCalendarProvider     — JVM stub
  work/
    CalendarSyncWorker       — CoroutineWorker: diff → apply → update map
    CalendarSyncWorkScheduler — interface: enqueueSync, cancelSync
    AndroidCalendarSyncWorkScheduler — WorkManager with EXPONENTIAL back-off
    NoopCalendarSyncWorkScheduler     — JVM stub
  presentation/
    CalendarSyncViewModel    — UiState machine for the settings screen
    CalendarSyncSettingsScreen — Compose UI: permission gate, enable toggle, calendar picker
  di/
    CalendarSyncDiModule     — registers ViewModel; repository from platform module
```

### Key design decisions

| Decision | Rationale |
|---|---|
| One-way (Task → calendar) | Avoids bidirectional conflict resolution; matches Orgzly pattern |
| Separate feature module | Can be disabled without affecting core calendar display |
| `CalendarProviderPort` in commonMain | Standard KMP pattern; enables FakeCalendarProvider for JVM tests |
| `asSyncAdapter` + `account_type=com.singularity.todo` | Required for Google Calendar to accept events; account_name = scopedUserId for multi-profile isolation |
| Deep-link `singularity://task/{id}` in description | Intent-filter opens correct task in app; survives event rename |
| `RecurrenceRule` sealed interface | Type-safe; `Custom(rrule)` escape hatch for unsupported patterns |
| `SyncDiffMerge` pure diff | All domain logic is unit-testable without Android |
| WorkManager with EXPONENTIAL back-off | Survives process death and battery constraints |
| DataStore-backed settings | Separate file from main preferences; isolated per profile |

### RRULE generation

```
Daily     → FREQ=DAILY
Weekly    → FREQ=WEEKLY;BYDAY=MO,WE,FR
Monthly   → FREQ=MONTHLY;BYMONTHDAY=15
Yearly    → FREQ=YEARLY
Custom    → pass-through (user's raw recurringPattern string)
```

### Sync flow

1. `CalendarSyncWorker.doWork()`:
   a. Read `taskRepo.observeAll()` → `List<Task>`
   b. For each task with dueDate, read `reminderRepo.observeByTaskId(taskId)`
   c. Map to `CalendarSyncEvent` via `CalendarEventMapper`
   d. Read `taskMapDao.getAll()` → existing eventIds
   e. Compute diff via `SyncDiffMerge.diff(existingMap, desiredEvents)` → `List<SyncPlan>`
   f. Execute each plan via `calendarProviderPort` (insert/update/delete)
   g. Update `taskMapDao` (delete stale, insert new mappings)
   h. Set `lastSyncedAt` and `status=Idle`

2. On failure → `Result.retry()` with exponential back-off (WorkManager policy)

3. `CalendarSyncWorkScheduler.enqueueSync()` is called from:
   - `CalendarSyncViewModel.processIntent(SyncNow)`
   - MR-0 `SyncOutboxProcessor` after Supabase push (on every task change)

### Permission flow

- `CalendarPermissionRequester.hasPermissions(context)` checks `READ_CALENDAR` + `WRITE_CALENDAR`
- If not granted → `CalendarSyncSettingsScreen` shows permission gate with "Grant Access" button
- Button calls `ActivityResultContracts.RequestMultiplePermissions`
- On deny → snackbar with "Open Settings" to go to app permissions page

### What is NOT included (deferred)

- Two-way sync (calendar → Task)
- Editing events from within the app
- Calendar → Task deep-link when tapping system calendar event
- Periodic background sync without WorkManager (not needed since Outbox triggers on every task change)

## Consequences

- **Positive**: Users get free calendar notifications for tasks; multi-profile isolates calendar accounts
- **Positive**: JVM tests cover all domain logic (mappers, diff, generation) via `FakeCalendarProvider`
- **Negative**: `WRITE_CALENDAR` is a dangerous permission; users may be hesitant
- **Negative**: Google Calendar API rate limits apply (handled by WorkManager back-off)

## Links

- Inspired by: [Orgzly](https://github.com/orgzly/orgzly-android) sync model
- RFC 5545: https://datatracker.ietf.org/doc/html/rfc5545#section-3.3.10
- Android CalendarContract: https://developer.android.com/reference/android/provider/CalendarContract
