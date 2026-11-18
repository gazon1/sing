---
title: "ON_RESUME is enough: on the measured platform the notification permission cannot go stale without a restart or a resume"
date: 2026-11-16
status: accepted
tags: [android, notifications, permissions, lifecycle, reminders]
---

# ON_RESUME is enough: on the measured platform the permission cannot go stale without a restart or a resume

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

### The measurement

**Conditions.** One physical device — Android 15 / API 35, `RMX3938` — with the app
built at `e30a09c` and `targetSdk 36`, measured on 2026-11-16. The instrument was
`logcat -b events` (`wm_on_*_called`, `am_kill`) plus `dumpsys package` and
`dumpsys window`. This is one device and one API level. It records what that platform
did; it is not a contract that every supported release or OEM will always behave the
same way.

### What was observed

1. **The shade is a window.** Expanding and collapsing the notification shade emitted
   no `wm_on_pause_called`, `wm_on_stop_called` or `wm_on_resume_called` — the app
   window only lost focus. So a grant change made *from the shade itself* would not
   deliver `ON_RESUME`.
2. **Revoking the permission killed the process.** Revoking it while the app was
   resumed:

   ```
   $ adb shell pm revoke com.singularity.todo android.permission.POST_NOTIFICATIONS
   $ adb shell pidof com.singularity.todo          # empty
   I am_kill : [0,25046,com.singularity.todo,0,permissions revoked,305660]
   ```

   And the real thing — the app's notification settings screen, the switch tapped while
   the app was paused:

   ```
   I am_kill : [0,24502,com.singularity.todo,700,PermissionHelper,333744]
   ```

3. **Granting did not kill the process.** `pid` was unchanged across `pm grant`.
   Returning from system settings delivered `wm_on_resume_called` on `MainActivity`,
   and the runtime permission dialog was answered by the
   `ActivityResultContracts.RequestPermission` callback.

### What is concluded from it

- A revocation cannot leave a stale screen *on this build*: the process is killed, so
  the remembered Compose state no longer exists, and the next launch rebuilds it from
  `readNotificationPermission(context)`. The observed end-to-end story — toggle off in
  Settings, press back — relaunched the app (`am_proc_start ... top-activity`, fresh
  `wm_on_create_called`) and the state was correct. There is no screen left to display
  a stale value.
- A grant reaches the app either on `ON_RESUME` (system settings) or through the
  launcher callback (the dialog), so the re-read covers it.
- Therefore the `ON_RESUME`-only observer covers every path exercised here, and the
  case that worried the review (option 2 above) has no reachable trigger on this
  platform.

### What these conclusions do not cover

The mechanism behind observation 2 is AOSP's `PermissionManagerService` calling
`killUid(REASON_PERMISSION_CHANGE)` on a runtime-permission change, which is why the
behaviour is expected beyond this one device. It was **not** measured on API 33 or
API 34, and it was not measured on other OEM builds. So the decision is not a promise
that every supported version behaves identically; it is a decision that the surviving
paths are covered on the measured platform, and that the residual is observable rather
than hidden. If a supported release stops killing the process on revoke, this decision
is the thing to re-measure: the guarantee rests on the kill, not on the resume.

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
- The composable wiring is covered on the device by
  `NotificationPermissionRequesterComposeTest`: it mounts the production composable
  under a controlled `LifecycleOwner` and asserts that a resume reaches the rendered
  state, that disposal removes the observer, and that swapping the owner moves it. The
  grant is answered by a fake `Context`, so that test covers the wiring, not the OS —
  the OS half is what the measurement above records.
- If a future Android release stops killing the process on revoke, this decision is the
  thing to re-measure — the guarantee it rests on is the kill, not the resume.

## Links

- `shared/src/androidMain/kotlin/com/singularity/todo/core/notifications/NotificationPermissionRequester.kt`
- `shared/src/androidHostTest/kotlin/com/singularity/todo/core/notifications/NotificationPermissionRequesterTest.kt`
- `androidApp/src/androidTest/kotlin/com/singularity/todo/ui/NotificationPermissionRequesterComposeTest.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/screen/TaskDetailContent.kt` (the consumer)
- `docs/decisions/2026-10-07-reminders-capability-gate.md` (the write path, which reads capability live)
- Issue #2903, PR #2996
