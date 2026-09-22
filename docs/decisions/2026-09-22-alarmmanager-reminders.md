---
title: "AlarmManager + BootReceiver для reminders ( Orgzly pattern)"
status: accepted
date: 2026-09-22
deciders: ["ZCode Agent"]
labels: [reminders, android, alarms, architecture]
---

## Context

`ReminderScheduler.start()` никогда не вызывался → reminders не работали.
`ReminderBroadcastReceiver` не был зарегистрирован в манифесте.
Старая архитектура: polling каждые 60 секунд.

## Analysis

Изучено Orgzly-android: использует `AlarmManager.setAlarmClock` + single `BroadcastReceiver` (OrgzlyReceiver) с несколькими action-filter.
`setAlarmClock` не требует `SCHEDULE_EXACT_ALARM` permission, exempt от Doze.

**WorkManager vs AlarmManager:**

| Критерий | WorkManager | AlarmManager |
|---|---|---|
| Exact timing in Doze | ❌ (approximate only) | ✅ |
| Survives process death | ✅ | ✅ |
| Survives reboot | ✅ (with `ExistingPeriodicWorkPolicy.KEEP`) | ✅ (BOOT_COMPLETED) |
| Permission | `SCHEDULE_EXACT_ALARM` | None (setAlarmClock exempt) |
| Dependency | Heavy (WorkManager) | None (built-in) |
| Multi-action receiver | N/A | ✅ (Orgzly pattern) |

## Decision

**AlarmManager + BootReceiver + Multi-action AlarmReceiver (Orgzly pattern)**

```
AlarmManagerReminderScheduler  → setAlarmClock(fireAt, pendingIntent)
AlarmReceiver (android:name)    → 4 actions:
  ACTION_REMINDER_FIRE         → stale-text fix: read DB, post notification
  ACTION_POMODORO_PHASE_END    → post "Work ended / Back to work"
  ACTION_BOOT_COMPLETED        → catch-up (past-due capped 20) + reschedule all
  ACTION_REMINDER_DATA_CHANGED → reschedule all
```

**Stale-text fix:** title/body не передаются через PendingIntent extras.
Вместо этого `AlarmReceiver.handleReminderFire()` читает свежий task title из Room DB
в момент fire.

**Multi-profile:** alarm keys scoped as `"reminder:${userId}:${id}"`.

**Catch-up on boot:** `watchDueBefore(now)` → capped 20 past-due reminders → post + delete one-shot.

**Permission:** `RECEIVE_BOOT_COMPLETED` — для restore alarms after reboot.
`SCHEDULE_EXACT_ALARM` не нужен (setAlarmClock exempt).

## Consequences

- ✅ Reminders работают после reboot (catch-up)
- ✅ Stale-text fix: fresh task title в notifications
- ✅ Multi-profile isolation
- ✅ No `SCHEDULE_EXACT_ALARM` permission
- ❌ Catch-up capped at 20 to avoid notification storm

## Deleted

- `ReminderScheduler` class (old polling scheduler)
- `ReminderBroadcastReceiver` (old, not registered in manifest)
- `PomodoroRepository` (0 prod call sites)
- `SCHEDULE_EXACT_ALARM` from manifest

## Links

- Implementation: `AlarmManagerReminderScheduler.kt`, `AlarmReceiver.kt`, `AndroidNotifier.kt`
- Pure logic: `ReminderFireLogic.kt`, `ReminderScheduler.kt` (interface)
- Skill: `.agents/skills/singularity-todo-notifications/SKILL.md`
