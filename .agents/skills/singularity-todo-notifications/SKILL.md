---
name: singularity-todo-notifications
description: Notification port pattern for KMP with notify-send/at on JVM, AlarmManager+BootReceiver on Android. Use when building cross-platform reminder and notification systems.
---

# Singularity TODO — Notification Port Pattern

This skill documents the notification architecture in the Singularity TODO KMP app.

## Current Architecture (2026-09-22)

```
NotificationPort (interface — commonMain)
    │
    ├── JvmNotificationPort (jvmMain)
    │       ├── notify-send (immediate notifications)
    │       └── at daemon (scheduled notifications)
    │
    └── AndroidNotificationPort (androidMain)  ← stub: scheduling moved to AlarmManager
            └── NotificationManagerCompat (channel only)

AndroidNotifier (androidMain)
    └── NotificationManagerCompat.notify() — called by AlarmReceiver

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

## NotificationPort Interface

```kotlin
interface NotificationPort {
    val isAvailable: Boolean
    suspend fun scheduleAt(key, title, body, fireAtEpochMs, payload, viewId)
    suspend fun cancel(key: String)
    suspend fun cancelAll()
}
```

On Android: `scheduleAt`/`cancel` are **stubs** — all scheduling goes through
`AlarmManagerReminderScheduler`. `NotificationPort` remains to satisfy any callers.

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

Created by both `AndroidNotificationPort.init {}` and `AndroidNotifier {}`.

## Fake for Testing — FakeNotificationPort

```kotlin
class FakeNotificationPort(
    override val isAvailable: Boolean = true
) : NotificationPort {
    data class Scheduled(val key, val title, val body, val fireAtEpochMs, val payload)
    val scheduled = mutableListOf<Scheduled>()
    val canceled = mutableListOf<String>()

    override suspend fun scheduleAt(key, title, body, fireAtEpochMs, payload) {
        scheduled.add(Scheduled(key, title, body, fireAtEpochMs, payload))
    }
    override suspend fun cancel(key: String) { canceled.add(key) }
    override suspend fun cancelAll() { canceled.addAll(scheduled.map { it.key }) }
    fun reset() { scheduled.clear(); canceled.clear() }
}
```

## Key Files

| File | Purpose |
|---|---|
| `shared/src/commonMain/.../core/notifications/NotificationPort.kt` | Interface |
| `shared/src/androidMain/.../core/notifications/AndroidNotificationPort.kt` | Stub (channel only) |
| `shared/src/androidMain/.../core/notifications/AndroidNotifier.kt` | Posts notifications |
| `shared/src/androidMain/.../feature/reminders/AlarmManagerReminderScheduler.kt` | setAlarmClock scheduling |
| `shared/src/androidMain/.../feature/alarms/AlarmReceiver.kt` | Multi-action BroadcastReceiver |
| `shared/src/jvmMain/.../core/notifications/JvmNotificationPort.kt` | notify-send + at |
| `shared/src/jvmMain/.../feature/reminders/JvmReminderScheduler.kt` | no-op |
| `shared/src/commonMain/.../feature/reminders/ReminderScheduler.kt` | Interface |
| `shared/src/commonMain/.../feature/reminders/ReminderFireLogic.kt` | Pure business logic |

## When to Use This Pattern

- Building reminder/notification features in a KMP app targeting JVM and Android
- Exact timing required (not approximate) — use `setAlarmClock`
- Need to survive process death and reboot
- Multi-action receiver to consolidate broadcast handling
