---
title: "ON_RESUME is enough: the notification permission cannot go stale without a process restart or a resume"
date: 2026-11-16
status: accepted
tags: [android, notifications, permissions, lifecycle, reminders]
---

# ON_RESUME is enough: the permission cannot go stale without a restart or a resume

## Context

`rememberNotificationPermissionRequester` captured the notification permission once
(`remember { permissionGranted() }`) and never re-read it, so the reminder sheet kept
saying «Уведомления выключены» after the user turned notifications on in system
settings (#2903). The fix re-reads the grant on `ON_RESUME` through a
`LifecycleEventObserver` registered in a `DisposableEffect`.

Review of that fix (#2996) raised the remaining question: #2903 lists a third way the
permission can change — *the user revokes `POST_NOTIFICATIONS` from the notification
shade*, without a trip to Settings. If the shade changed the permission without the
Activity passing through `ON_PAUSE`/`ON_RESUME`, the `ON_RESUME`-only fix would leave
the state stale, and the answer would have to be an extra window or lifecycle callback.

That is a question about platform behaviour, and no amount of reading the diff answers
it. So it was measured.

## Idea

Three candidate answers:

1. **`ON_RESUME` only** — if every reachable permission change is followed by a resume
   or a process restart, nothing else is needed.
2. **Add a window-focus or `onActivityResult`-style callback** — if the shade can change
   the grant while the Activity stays resumed.
3. **Poll** — rejected on sight: it burns battery to discover a state the platform will
   announce, and it still has a window of wrongness.

## Decision

Option 1. Keep the `ON_RESUME` observer as the whole contract. No window callback, no
polling, no second state holder.

## Rationale

Measured on Android 15 / API 35 (targetSdk 36), app at `e30a09c`, using
`logcat -b events` (`wm_on_*_called`, `am_kill`) plus `dumpsys package` and `dumpsys
window`.

**The shade premise is structurally true.** Expanding and collapsing the notification
shade emits no `wm_on_pause_called`, `wm_on_stop_called` or `wm_on_resume_called` — the
shade is a window, not an Activity transition, and the app window merely loses focus.
So a grant change made *from the shade itself* would indeed not deliver `ON_RESUME`.

**But every path that turns notifications off kills the process.** Revoking the
permission while the app was resumed:

```
$ adb shell pm revoke com.singularity.todo android.permission.POST_NOTIFICATIONS
$ adb shell pidof com.singularity.todo          # empty
I am_kill : [0,25046,com.singularity.todo,0,permissions revoked,305660]
```

And the real thing — the app's notification settings screen, the «Вкл.» switch tapped
while the app was paused:

```
I am_kill : [0,24502,com.singularity.todo,700,PermissionHelper,333744]
```

This is platform behaviour, not an OEM quirk: revoking a runtime permission goes
through `PermissionManagerService` and calls `killUid(REASON_PERMISSION_CHANGE)`. After
the kill the remembered Compose state does not exist. The next launch builds it from
`remember(context) { mutableStateOf(readNotificationPermission(context)) }`, which reads
the live grant. The observed end-to-end story — toggle off in Settings, press back —
relaunched the app (`am_proc_start ... top-activity`, fresh `wm_on_create_called`) and
the state was correct. **A revoked permission cannot be displayed stale, because there
is no screen left to display it.**

**Every path that turns notifications on delivers a resume or the launcher callback.**
Granting does not kill the process (`pid` unchanged across `pm grant`), and:

- returning from system settings delivers `wm_on_resume_called` on `MainActivity`;
- the runtime permission dialog is answered by the
  `ActivityResultContracts.RequestPermission` callback, which the implementation already
  consumes via `hasPermissions = granted`.

So the state is refreshed exactly where it can be wrong, and the case that worry about
(point 2 above) has no reachable trigger.

Two residuals are known and accepted rather than fixed:

- **Multi-window / freeform.** A resumed Activity in one pane can stay `RESUMED` while
  the permission is granted from another pane, so the *grant* could be stale until the
  next resume. Turning it off still kills the process. Adding a callback for this would
  mean adding a code path that only a split-screen user can exercise, with the state
  still converging on the next resume; not worth it while the alternative costs an
  always-registered listener.
- **Per-channel importance.** Blocking just the `singularity_reminders` channel is not
  an app-level permission and is outside this port's contract. The port answers «may we
  post», not «will this channel show», and the write path
  (`docs/decisions/2026-10-07-reminders-capability-gate.md`) already asks
  `Notifier.isSupported` live.

## Consequences

- The observer stays as written: one `LifecycleEventObserver` filtering `ON_RESUME`,
  registered and removed by `DisposableEffect`.
- The test drives a real `LifecycleRegistry` — registration on a resumed screen (which
  replays `ON_RESUME`), background/foreground trips, and `removeObserver` — instead of
  calling `onStateChanged` by hand, so registration and cleanup are covered rather than
  assumed.
- The composable wiring itself (that `DisposableEffect` adds *this* observer) is still
  untested: `androidHostTest` deliberately declares no Compose test rule. That gap is
  written down in the test's KDoc rather than papered over.
- If a future Android release stops killing the process on revoke, this decision is the
  thing to re-measure — the guarantee it rests on is the kill, not the resume.

## Links

- `shared/src/androidMain/kotlin/com/singularity/todo/core/notifications/NotificationPermissionRequester.kt`
- `shared/src/androidHostTest/kotlin/com/singularity/todo/core/notifications/NotificationPermissionRequesterTest.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/screen/TaskDetailContent.kt` (the consumer)
- `docs/decisions/2026-10-07-reminders-capability-gate.md` (the write path, which reads capability live)
- Issue #2903, PR #2996
