---
title: "Desktop reminders fire through a systemd --user timer that runs this app's own launcher"
date: 2026-10-07
status: accepted
tags: [reminders, desktop, systemd, notifications]
---

## Context

`JvmReminderScheduler` had been three no-ops with `isSupported = false`. That was honest —
the desktop has no alarm manager — and it meant the Desktop could not remind anyone at
all. The capability gate (`2026-10-07-reminders-capability-gate.md`) made the absence
visible and left a written landing checklist rather than a design.

Two things were already built and waiting:

- A `Notifier` port (`core/notifications/Notifier.kt`) that can only *display* something.
  Deliberately shaped so it has no way to enumerate or cancel system jobs — the property
  whose absence got the old `NotificationPort` deleted.
- The agreed design itself: a `systemd --user` timer per reminder, keyed to this app only.

## The hard part

A `systemd-run` transient unit is a fixed command line. It has no database access. But a
reminder's text is computed **at fire time** from a fresh read of the task title — the
whole point of `ReminderFireLogic`, so that renaming a task after arming changes what the
notification says.

Three ways out, and the two wrong ones are worth recording:

1. **Bake the title into the unit.** Two lines of shell, and wrong twice. The title goes
   stale on rename — the bug the fire-time read was written to eliminate. And it puts
   user-authored text on a command line whose escaping rules are systemd's, not a shell's
   and not `ProcessBuilder`'s. The repo has already been burned by exactly this class of
   bug: the deleted `at` backend.
2. **Add a title column to the reminder entity.** Forces a `SCHEMA_VERSION` bump and an
   `AutoMigration` — the single most expensive move available in this codebase — to store
   a value that is already derivable, and which is stale by construction.
3. **Make the unit run the app itself.** What was done.

## Decision

The transient unit runs the packaged Desktop launcher:

```
systemd-run --user --unit=singularity-reminder-<user>-<reminder>
    --on-calendar=@<epoch-seconds>
    --timer-property=AccuracySec=1s
    --setenv=DBUS_SESSION_BUS_ADDRESS=unix:path=$XDG_RUNTIME_DIR/bus
    -- /usr/bin/singularity-todo fire-reminder <reminderId> <userId>
```

`main` parses argv and returns before `singleWindowApplication`, so no window and no AWT
toolkit. The fire logic lives in `:shared` as `JvmReminderFire`, which takes its three
ports as parameters rather than resolving them — so it needs no DI binding, and therefore
no `platform-seams.tsv` row: it is not a seam, because Android never binds it and fires
the same reminder through `AlarmReceiver`.

### Why the app's own binary and not a helper

A helper binary is a reminder that silently does not fire on every machine where nobody
remembered to install it. The launcher is already installed wherever the app is.

### Why `--on-calendar=@<epoch>`

systemd resolves `@<epoch>` against the **realtime** clock, so a machine that suspends
wakes up and still fires at the wall-clock time the user chose. A relative `--on-active`
timer counts monotonic time and fires late. Second precision is all the calendar syntax
offers; a reminder up to a second early is not perceptible.

### Why `DBUS_SESSION_BUS_ADDRESS` is set explicitly

`notify-send` needs the session bus, and a `systemd --user` unit does not inherit one — the
user manager's environment is not the graphical session's. Without this the unit runs,
succeeds, and displays nothing: the worst outcome, because every exit code says the
reminder fired. The socket is derived from `XDG_RUNTIME_DIR`, which is where it lives by
specification, and the address is **omitted** rather than guessed when that variable is
unset, because a wrong address is worse than none.

### Why `isSupported` is a conjunction

It is `systemd-run --user` present **and** `Notifier.isSupported`. Both halves are
load-bearing: systemd alone arms a timer whose `notify-send` finds no bus and exits
non-zero — a reminder that fires and displays nothing, which is worse than one that never
fires, because the user does not know it is missing. `notify-send` alone cannot arm
anything.

So a container, a CI runner, or an SSH session reports `false`, and callers refuse before
persisting a row. That is the capability gate, still doing its job, now on a narrower
definition of "supported".

### Why cancellation is still silent

Unchanged from the stub, and now for a sharper reason: the state being asked for — "not
armed" — is already true. Every command is `systemctl --user stop` on a **name this app
chose**. There is no enumeration step anywhere in this class, which is precisely what
`atq`/`atrm` did to every unrelated job on the host.

## What it does not survive: logout

A transient unit dies with the user manager. Reboot, logout, or `systemctl --user
daemon-reload` all leave the reminder rows in the database with no timer behind them.

So the desktop entry point **re-arms every future reminder at launch**, inside the same
background scope that resolves the profile. This is the Desktop counterpart to Android's
`ACTION_BOOT_COMPLETED` catch-up, and it is the whole reason a Desktop reminder survives a
restart at all.

It inherits one limit from `AlarmReceiver.rescheduleAll`, deliberately: `observeAll` is
scoped to the active profile, so reminders belonging to another profile are re-armed when
that profile becomes active, not at every launch. Making them cross-profile would need a
profile-independent query that nothing else in the codebase has a reason to have.

`AlarmManager.setAlarmClock` survives all three events. This does not, and that gap is
stated here rather than discovered by a user whose 18:00 reminder did not arrive.

## Consequences

- Desktop reminders work, and the text they show is read at fire time like Android's.
- A reminder's whole path — arm, fire, retire — is asserted by tests, and the argv the
  unit depends on is pinned as a whole, because no test executes a real systemd unit and a
  changed shape would otherwise surface only as a reminder that never arrives.
- Re-arming at launch is a full table scan of the reminders table. That is bounded by the
  number of reminders a user has, not by anything system-wide.
- The launcher path (`/usr/bin/singularity-todo`) is derived from jpackage's `packageName`
  and is a constructor parameter, so a test asserts the argv without depending on install
  layout. If the packaging name changes, the reminder silently stops arming — and
  `systemd-run` failing produces a thrown error, not a silence, which is the intended
  failure direction.
- `JvmReminderFire` deliberately does not take a DI binding. Adding one would have forced a
  registry row for a class only one platform has.

## Links

- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/reminders/JvmReminderScheduler.kt`
- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/reminders/JvmReminderFire.kt`
- `shared/src/jvmMain/kotlin/com/singularity/todo/core/notifications/JvmNotifier.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/reminders/JvmReminderSchedulerCapabilityTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/reminders/JvmReminderFireTest.kt`
- `desktopApp/src/main/kotlin/com/singularity/todo/main.kt` (`main`, `rearmReminders`)
- Lands the checklist in: `2026-10-07-reminders-capability-gate.md`
- Not to be revived: `2026-10-06-notification-port-deleted-because-it-cancelled-other-peoples-jobs.md`
- Prior: `2026-10-06-desktop-background-work-executor.md`