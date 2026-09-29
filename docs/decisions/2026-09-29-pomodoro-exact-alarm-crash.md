---
title: "Starting a Pomodoro crashed the app — exact-alarm permission was neither declared nor guarded"
date: 2026-09-29
status: accepted
tags: [pomodoro, android, crash, permissions]
---

## Context

Tapping a focus-task chip on the Pomodoro tab killed the process:

```
java.lang.SecurityException: Caller com.singularity.todo needs to hold
android.permission.SCHEDULE_EXACT_ALARM or android.permission.USE_EXACT_ALARM
  at android.app.AlarmManager.setAlarmClock
  at ...PomodoroAlarmScheduler.schedulePhaseEnd(PomodoroAlarmScheduler.kt:32)
  at ...AndroidPomodoroTimer.start(AndroidPomodoroTimer.kt:82)
  at ...PomodoroScreen.kt:80        ← the chip's onClick
```

Two independent defects stacked:

1. The manifest declared neither `USE_EXACT_ALARM` nor `SCHEDULE_EXACT_ALARM`,
   while `PomodoroAlarmScheduler` called `setAlarmClock`, which has required
   both since API 31.
2. The call sat on a suspend boundary reached straight from a composable's click
   handler, with nothing catching the exception. So a permission problem became
   a process death rather than a degraded timer.

The same scheduler backs task reminders, so the same crash was reachable from
reminders too.

## Idea

1. Declare the permission only.
2. Catch and degrade only.
3. Both.

## Decision

We did (3).

`USE_EXACT_ALARM` is the grant-on-install variant intended for alarm and timer
apps, which is what this is; `SCHEDULE_EXACT_ALARM` is declared alongside it
because the app also schedules wall-clock task reminders. The manifest carries a
comment saying the declaration is not sufficient on its own.

`schedulePhaseEnd` now catches `SecurityException` and falls back to
`setAndAllowWhileIdle` (inexact, survives Doze, can be deferred), logging a
warning that names the consequence. A Pomodoro phase that ends a few minutes
late is a strictly better outcome than a crash.

## Rationale

Declaring the permission alone leaves the crash reachable: a user can revoke an
exact-alarm grant on most Android versions, and OEMs have their own policies.
Since a permission the user can take away is not a guarantee, a code path that
converts "permission denied" into "process dies" is wrong regardless of the
manifest. Both halves are the fix; either alone is half a fix.

The fallback is a real degradation, not a stub — `setAndAllowWhileIdle` still
fires, just without exact timing — and it is logged rather than swallowed so the
drift is visible when it happens.

## Consequences

- Starting a Pomodoro no longer crashes with or without the permission; without
  it the phase ends late instead of not at all.
- `Maestro/flows/pomodoro/*.yaml` cover start, pause/resume, skip and stop. They
  do not cover the alarm actually firing — that would need a 25-minute wait, and
  the reminder path is out of scope for the Maestro suite.
- The reminder scheduler shares this concern; a future reminder flow will want
  the same guard if its scheduling path is separate.

## Links

- `shared/src/androidMain/kotlin/com/singularity/todo/feature/pomodoro/PomodoroAlarmScheduler.kt`
- `androidApp/src/main/AndroidManifest.xml`
- `Maestro/flows/pomodoro/02-start-focus-task.yaml`
- Related: `2026-09-29-emulator-launch-recipe.md` (the run-loop that surfaced it)
