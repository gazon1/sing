---
date: 2026-10-07
status: accepted
slug: reminders-capability-gate
---

# Reminders declare their capability; two of the three reminder paths were lying

## Context

`JvmReminderScheduler` implemented `schedule`, `cancel` and `cancelByTask` as empty
bodies. On Desktop, setting a reminder on a task wrote a row to `task_reminders` and
reported success; no alarm was ever armed, because there is no Desktop alarm
scheduler. The row renders as a live reminder forever, so the user was left with a
reminder that could not arrive and nothing to indicate otherwise.

Investigating that surfaced a second, larger instance of the same class of defect:

| Path | Row persisted | Alarm armed | Platforms affected |
|---|---|---|---|
| Task reminder (`TaskRemindersSlot`) | yes | **no on Desktop** | Desktop only |
| Project reminder (`ProjectDetailViewModel.setReminder`) | yes | **no, anywhere** | **all platforms** |

For project reminders there is no `ProjectReminderScheduler` on any platform, and
`AlarmReceiver` has no project branch. `docs/decisions/2026-09-30-project-reminder-own-table.md`
says so explicitly ("the alarm is scheduled, not fired… the fire path is the remaining
half"), and the UI shipped anyway. So Android users could also pick a project reminder
that would never arrive — the same lie, on every platform.

Two KDoc sentences compounded it. `ProjectReminder`'s KDoc pointed at
`[ProjectReminderScheduler]`, a class that does not exist; IDEs render such a link as a
resolvable reference, so it read as documentation of an implemented thing.
`ReminderPickerSheet`'s KDoc claimed "selecting any offset dismisses the sheet without
side-effects" — which was simply false, the VM does write the row. An agent reading
either file would conclude the feature was either complete or inert, and neither reading
would match the code.

## Idea

Two options, and the choice is not really about the flag.

1. **Delete the project reminder UI.** The reminder button, sheet, intent, repository
   and offset maths are all real and tested. Deleting the entry point makes the app
   honest, but leaves the whole vertical slice unwired — which is precisely what
   `scripts/find-unwired-surfaces.py` exists to flag. It trades a visible lie for an
   invisible half-feature, and discards work that the scheduler follow-up needs.
2. **Declare capability at the seam, refuse before persisting, gate the UI.** Add
   `isSupported` to the port, read it in the caller before the write, and disable the
   project bell behind a named constant.

## Decision

Take option 2.

- `ReminderScheduler` gains `val isSupported: Boolean`. Android
  (`AlarmManagerReminderScheduler`) reports `true`; JVM (`JvmReminderScheduler`) reports
  `false`.
- `TaskRemindersSlot.setReminder` checks it **before** `reminderRepo.upsert` and reports
  an error instead. Clearing is deliberately not gated: nothing was armed, so a delete
  must remain a harmless no-op rather than an error the user cannot act on.
- The project bell is disabled behind `PROJECT_REMINDERS_SUPPORTED = false`, which is a
  compile-time constant rather than a port read — there is no scheduler to ask, so
  there is no runtime fact to propagate.
- Fakes take `isSupported` as a parameter. A fake that always claims support would let
  the gate's own test pass whether or not the gate existed.

The gate is placed *before the write*, not before the schedule call. This is the whole
point: the alarm is invisible and the row is not. Refusing at schedule time would leave
exactly the artifact that misleads the user.

## Rationale

**The implementation is the authority, not the UI.** `isSupported` is read from the port
rather than computed in common code as `platform == "Android"`. A capability duplicated
per call site is one that drifts; the JVM implementation is already the thing that knows
it cannot arm anything. This mirrors `CalendarSyncUiState.isSupported`, which takes its
value from the provider's failure rather than a hardcoded platform check.

**Two constants, not one, because the facts differ.** `isSupported` is false on Desktop
only. `PROJECT_REMINDERS_SUPPORTED` is false everywhere. Merging them would have
implied that Desktop is the only place project reminders fail, which is the opposite of
the truth — and is how the original KDoc conflated them.

**The button stays, disabled.** The slice's model, persistence and offset maths are
correct and covered by four VM tests. A scheduler follow-up supplies one missing piece
and flips one constant. Removing the entry point would have made that follow-up a
reconstruction.

## Consequences

- Desktop users selecting a task reminder now get an error naming the platform, instead
  of a stored reminder that never fires. Android task reminders are unaffected.
- Project reminders are unreachable on all platforms until a scheduler exists. This is a
  deliberate reduction in reachable features: the previous state let users create a
  reminder that could not fire.
- The `at`-based backend stays deleted. Its replacement must be `systemd --user`
  timers, keyed to this app — see
  `2026-10-06-notification-port-deleted-because-it-cancelled-other-peoples-jobs.md`.
  When it lands, flip `isSupported`, delete `JvmReminderSchedulerCapabilityTest`'s
  first assertion, and set `PROJECT_REMINDERS_SUPPORTED` only once a project scheduler
  exists.
- Three false KDoc claims were corrected. Where a file documents the absence of a
  dependency, it now says so explicitly rather than naming a class that does not exist.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/reminders/ReminderScheduler.kt`
- `shared/src/commonMain/.../reminders/ProjectReminder.kt`
- `shared/src/jvmMain/.../reminders/JvmReminderScheduler.kt`
- `shared/src/commonMain/.../tasks/presentation/viewmodel/slot/TaskRemindersSlot.kt`
- `shared/src/commonMain/.../projects/presentation/screen/ProjectDetailBody.kt`
- `shared/src/commonTest/.../slot/TaskRemindersSlotTest.kt`
- `shared/src/jvmTest/.../reminders/JvmReminderSchedulerCapabilityTest.kt`
- `shared/src/jvmTest/resources/platform-seams.tsv` (row `ReminderScheduler`)
- Prior: `2026-09-30-project-reminder-own-table.md`, `2026-10-06-notification-port-deleted-because-it-cancelled-other-peoples-jobs.md`