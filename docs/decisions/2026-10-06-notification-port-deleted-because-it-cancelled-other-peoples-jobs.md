---
title: NotificationPort is deleted, because its JVM half cancelled other people's jobs
date: 2026-10-06
tags: [notifications, desktop, seam, dead-code]
status: accepted
---

## Context

`NotificationPort` was bound in both platform modules and looked entirely healthy:

- both implementations present, neither a no-op class;
- `isAvailable` genuinely probed the system;
- the seam registry classified it `real`.

It was also **injected by nothing**. No production file referenced the type outside the two
`PlatformModule` bindings and the test doubles.

Worse, its JVM implementation was a landmine with three separate defects:

**1. It cancelled jobs it did not own.** `cancelAll()` ran `atq`, read every job id, and called
`atrm` on each one. `at(1)` is a system-wide queue: any job the user had scheduled from a shell,
outside this application, would be deleted. An app reaching into the host's scheduler and
clearing it wholesale is not something a user can reason about, and not something that needs a
permission prompt to be destructive.

**2. A missing daemon made reminders fire early.** `scheduleAt()` wrote the job to `at(1)` and,
on a non-zero exit, called `fireNow()` — posting the notification immediately. `isAvailable`
probes only `notify-send`, so on a host with libnotify but no `atd` the check passes and the
fallback fires. "Reminder set for 18:00" becomes "notification now", which is worse than not
supporting reminders at all.

**3. Scheduling was untrackable.** `scheduleAt()` never wrote the job file — only `cancel()` and
`cancelAll()` read and wrote it. So `cancel(key)` looked up a key that was never stored, found
nothing, and returned: a scheduled job could not be cancelled. The file itself was a raw
`java.io.File`, against the project's rule that file access goes through the `FileSystem` port.

## Why it was dead

Reminder scheduling moved. `AndroidNotificationPort`'s own KDoc says so — its `scheduleAt` and
`cancel` are documented stubs, with reminder delivery now handled by
`AlarmManagerReminderScheduler` posting through `AndroidNotifier`. The JVM half was simply never
updated to follow, and nothing noticed, because nothing called it.

## Decision

**Delete `NotificationPort` and both implementations.**

Nothing referenced it, so removal cannot change behaviour, and it removes a port that can
delete host state and mis-fire notifications. The Android `init` block that created a
notification channel is gone with it; if a channel is needed again it should be created by the
component that actually posts to it.

## The generalisation this forced

`find-unwired-surfaces.py` cannot see this class of defect. Its `orphan-binding` check
hard-filters to `CoreDiModule.kt` and reports only bindings that **nothing injects** — while
this was a binding nothing injected, sitting in `PlatformModule`, outside the filter, in a file
the scan does not visit.

So the seam registry gained a **`wiring`** column: `injected` or `unwired`, with a required
reason when unwired. `PlatformSeamGuardTest` now fails when a seam declared `injected` appears
in no production file outside its own two bindings. `NotificationPort` is exactly the case that
rule exists for.

Both new rules were checked with a positive control rather than trusted from a green run:
marking a live seam `unwired` with no reason fails the build, as it should.

## Consequences

- One fewer platform seam: 25 becomes 24.
- A Desktop reminder backend, when it arrives, will need a **new** notifier port. It must not
  resurrect this one — see the reminder workstream in issue #214. **Done**, as
  `core/notifications/Notifier.kt`: it can only display, never schedule, never cancel, never
  enumerate, so the failure this port embodied has no code path in its replacement. See
  `2026-10-07-desktop-reminders-systemd-user-timers.md`.
- Adding a binding to a `PlatformModule` now means adding a registry row. That is the point.

## Links

- `shared/src/jvmTest/resources/platform-seams.tsv` — the registry and its `wiring` column
- `scripts/find-unwired-surfaces.py` — the detector that cannot see platform bindings
- `feature/reminders/AlarmManagerReminderScheduler.kt` — where reminder delivery actually lives
- Issue #214 — Desktop reminders, landed as `2026-10-07-desktop-reminders-systemd-user-timers.md`