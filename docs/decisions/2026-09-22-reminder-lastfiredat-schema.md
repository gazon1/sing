---
title: Reminder `lastFiredAt` — schema migration + scheduler guard
date: 2026-09-22
status: accepted
tags: [reminders, database, scheduler]
---

# Reminder `lastFiredAt` — schema migration + scheduler guard

## Context

The `ReminderScheduler` in `feature/reminders/` polls every 60 seconds for reminders with `fire_at <= now`. Two problems exist:

1. **Recurring reminders are re-fired on restart.** If the app process is killed while a recurring reminder is pending (device sleep, OOM), the scheduler picks it up again on the next start and re-fires it, causing duplicate notifications.
2. **No audit trail on the entity.** `TaskReminderEntity` had no column to record when a reminder was last fired.

## Decision

1. **Add `last_fired_at` column** to `task_reminders` (Migration 14 → 15, nullable `Long`, indexed on `fire_at`). Existing rows read back as `null`.

2. **Add `lastFiredAt: Long?` field** to the domain `Reminder` data class. Updated `toReminder()` mapper copies the column value through.

3. **Add `ReminderRepository.markFired(reminderId, timestamp)`** — persists `last_fired_at` and `updated_at` in one shot.

4. **Guard in `ReminderScheduler.poll()`** — before firing a recurring reminder, check `lastFiredAt`. If `now - lastFiredAt < MIN_RECURRING_INTERVAL_MS (30 s)`, skip it. This catches the duplicate-fire case (device restart + pending reminders from before the crash/sleep).

5. **`MIN_RECURRING_INTERVAL_MS = 30_000L`** — 30 seconds is a safe floor. It is not a scheduling interval; it only prevents the same recurring reminder from being picked up twice within a 30-second window. The caller is responsible for re-scheduling recurring reminders with a proper next-fire-at time.

## Schema diff

```sql
-- Migration14To15 (auto, Room infers ADD COLUMN)
ALTER TABLE task_reminders ADD COLUMN last_fired_at INTEGER;
```

```kotlin
// Entities.kt
@ColumnInfo("last_fired_at") val lastFiredAt: Long? = null

// Reminder.kt
data class Reminder(..., val lastFiredAt: Long? = null)

// Daos.kt
@Query("UPDATE task_reminders SET last_fired_at = :lastFiredAt, updated_at = :updatedAt WHERE id = :id AND user_id = :userId")
suspend fun setLastFiredAt(id: String, userId: String, lastFiredAt: Long, updatedAt: Long)
```

## Consequences

- **Positive:** Duplicate recurring reminder fires are eliminated on app restart or after device wake.
- **Positive:** The `last_fired_at` column is available for future analytics (e.g., "last reminded at").
- **Neutral:** Pre-existing recurring reminders without `lastFiredAt` will fire immediately on next poll after upgrade (no worse than before).
- **Neutral:** Requires bump from schema v14 → v15 (`AppDatabase.version = 15`, `Migration14To15` registered).
- **No new test coverage** for the guard logic (Tier 3c is documented as a coverage gap per Round 1).
