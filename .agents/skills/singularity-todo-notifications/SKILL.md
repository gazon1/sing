---
name: singularity-todo-notifications
description: Notification port pattern for KMP with notify-send/at on JVM, AlarmManager+BootReceiver on Android. Use when building cross-platform reminder and notification systems.
---

# Singularity TODO — Notification Port Pattern

This skill documents the notification architecture in the Singularity TODO KMP app.

## Current Architecture (2026-09-22)

```
ReminderScheduler (interface — commonMain)
    │
    ├── AlarmManagerReminderScheduler (androidMain)
    │       └── AlarmManager.setAlarmClock — scheduled reminders
    │
    └── JvmReminderScheduler (jvmMain)  ← no-op

AlarmReceiver (androidMain) — multi-action BroadcastReceiver
    └── reads DB, then AndroidNotifier posts the notification

AndroidNotifier (androidMain)
    └── NotificationManagerCompat.notify()

ReminderScheduler (interface — commonMain)
    │
    ├── AlarmManagerReminderScheduler (androidMain)
    │       └── AlarmManager.setAlarmClock(fireAt, pending)
    │
    └── JvmReminderScheduler (jvmMain)  ← no-op

AlarmReceiver (androidMain) — multi-action BroadcastReceiver
    ├── ACTION_REMINDER_FIRE        → read DB, post notification (stale-text fix)
    ├── ACTION_POMODORO_PHASE_END  → post "Work ended / Back to work"
    ├── ACTION_BOOT_COMPLETED      → catch-up (≤20) + reschedule all
    └── ACTION_REMINDER_DATA_CHANGED → reschedule all
```

## Why AlarmManager (not WorkManager)?

| Критерий | WorkManager | AlarmManager |
|---|---|---|
| Exact timing in Doze | ❌ (approximate only) | ✅ (`setAlarmClock`) |
| Survives process death | ✅ | ✅ |
| Survives reboot | ✅ | ✅ (BOOT_COMPLETED) |
| Permission | `SCHEDULE_EXACT_ALARM` | None (`setAlarmClock` exempt) |
| Extra dependency | Heavy | None (built-in) |
| Multi-action receiver | N/A | ✅ (Orgzly pattern) |

## There is no NotificationPort — it was deleted

There used to be a notification port in `commonMain` with a JVM
implementation over `notify-send`/`at` and an Android stub. It was **removed on
2026-10-07** (`46d4da37`, "a port nothing injected was deleting every at job on the
host"): nothing ever injected it, so on the JVM every notification resolved to the
no-op branch and the reminder simply never fired. A port that is never injected is
worse than no port, because it reads as working.

Scheduling now goes through `ReminderScheduler` (per-platform) and delivery through
`AndroidNotifier` on Android. **Do not reintroduce a scheduling port** unless it is
wired in the same commit — that is precisely what the deleted port got wrong.

## ReminderScheduler Interface

```kotlin
interface ReminderScheduler {
    suspend fun schedule(reminder: Reminder)
    suspend fun cancel(id: ReminderId, userId: UserId)
    suspend fun cancelByTask(taskId: TaskId, userId: UserId)
}
```

## AlarmManagerReminderScheduler

Uses `AlarmManager.setAlarmClock` — exempt from Doze, no permission needed.

Key format: `"reminder:${userId.value}:${id.value}"` (cross-profile isolation).

**Stale-text fix:** title/body are NOT passed via PendingIntent extras.
`AlarmReceiver.handleReminderFire()` reads the fresh task title from Room DB at fire time.

## AlarmReceiver (Multi-action)

```kotlin
class AlarmReceiver : BroadcastReceiver(), KoinComponent {
    // goAsync() + CoroutineScope for clean lifecycle
    // Handles 4 actions
    // catch-up on BOOT_COMPLETED: watchDueBefore(now), takeLast(20)
}
```

## Notification Channel

Created by `AndroidNotifier` only.

## Fake for Testing

The old fake went with the port. Tests that need to assert on
scheduling now capture the `ReminderScheduler` directly — there is no common
interface left to fake, which is the point: a fake of a deleted type is a test
that passes without testing anything.

## Key Files

| File | Purpose |
|---|---|
| `shared/src/androidMain/.../core/notifications/AndroidNotifier.kt` | Posts notifications |
| `shared/src/androidMain/.../feature/reminders/AlarmManagerReminderScheduler.kt` | setAlarmClock scheduling |
| `shared/src/androidMain/.../feature/alarms/AlarmReceiver.kt` | Multi-action BroadcastReceiver |
| `shared/src/jvmMain/.../feature/reminders/JvmReminderScheduler.kt` | no-op |
| `shared/src/commonMain/.../feature/reminders/ReminderScheduler.kt` | Interface |
| `shared/src/commonMain/.../feature/reminders/ReminderFireLogic.kt` | Pure business logic |

## When to Use This Pattern

- Building reminder/notification features in a KMP app targeting JVM and Android
- Exact timing required (not approximate) — use `setAlarmClock`
- Need to survive process death and reboot
- Multi-action receiver to consolidate broadcast handling
