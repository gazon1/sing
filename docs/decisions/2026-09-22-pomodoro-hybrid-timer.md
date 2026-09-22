---
title: "Hybrid Pomodoro Timer — in-app ticker + AlarmManager.setAlarmClock"
status: accepted
date: 2026-09-22
deciders: ["ZCode Agent"]
labels: [pomodoro, android, alarms, architecture]
---

## Context

`AndroidPomodoroTimer` использовал in-app `delay()` loop для обновления UI каждую секунду.
При kill процесса таймер останавливался — фазы не переключались.

## Idea

Гибридная архитектура:
- **In-app 1 Hz ticker** — плавный countdown в UI, `delay()` в `viewModelScope`
- **OS-level `setAlarmClock`** — safety net на случай kill/process death
- **`phaseStartedAtEpochMs`** — recompute remaining time after pause/resume
- **`AtomicBoolean` race guard** — предотвращает double-trigger от simultaneous ticker + alarm

## Decision

`AndroidPomodoroTimer` получает `PomodoroAlarmScheduler` (создаёт `AlarmManager.setAlarmClock` на start/phase change, cancel на pause/stop).

`AlarmReceiver` слушает `ACTION_POMODORO_PHASE_END` и постит notification "Work ended / Back to work".

## Consequences

- ✅ Phase transitions гарантированы даже после process death
- ✅ Smooth countdown UI через 1 Hz ticker
- ✅ `phaseStartedAtEpochMs` в State (single source of truth) для точного recompute
- ❌ Requires `RECEIVE_BOOT_COMPLETED` permission
- ❌ Нужен catch-up логики (recompute from `Clock.now()`)

## Out of scope

- DataStore persistence state v1
- Foreground notification с progress
- Recurring Pomodoro sessions

## Links

- AlarmManager scheduling: `PomodoroAlarmScheduler.kt`, `AndroidPomodoroTimer.kt`
- Domain logic: `PomodoroDomain.kt` (`recomputeRemaining`, `nextPhase`)
- ADR: `2026-09-22-alarmmanager-reminders.md`
