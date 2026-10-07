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

### Follow-up: the flag alone was not enough

The first version of this change stopped here, and that was a mistake. `isSupported` is
a *declaration*, and a declaration is only as good as every callsite remembering to read
it. Auditing the callers found three `.schedule()` sites (`TaskRemindersSlot`,
`GoogleTaskApplier`, `AlarmReceiver`); the gate existed at exactly one of them, and the
other two were safe only by coincidence — `GoogleTaskApplier` merely shifts rows that
could no longer be created. A fourth caller would not have been so lucky.

So `JvmReminderScheduler.schedule` now **throws** `RemindersUnsupportedException`
instead of returning quietly. A silent no-op is the worst possible contract for a method
named `schedule`: it converts a missing capability into a successful-looking call.

The three methods are deliberately **not** symmetric, and that is the part most likely
to be "tidied up" later by someone assuming they should match:

| Method | On an unsupported platform | Why |
|---|---|---|
| `schedule` | throws | arming is the act that can silently lie |
| `cancel` | no-op | nothing was armed, so "cancelled" is already true |
| `cancelByTask` | no-op | same |

Throwing from `cancel` would break cleanup paths — deleting a task, dropping a due date,
importing from Google — for no benefit, and would leave a user unable to remove rows
written by an older build.

### Follow-up: a prose reason is not a test

Adding the check made the registry row describe a gate that nothing enforced. The
`reason` column said the UI checks `isSupported`; no rule could tell the difference
between that sentence being true and false. `platform-seams.tsv` therefore gained an
eighth column, `gate`, naming the symbol a seam's UI must read.

- A seam declaring a gate **must** have that symbol read in production code.
- A stub declaring no gate **must** justify it in `reason`, so `gate=-` is an audited
  decision rather than the path of least resistance.

Writing those two rules immediately failed on two stubs the fix had exposed rather than
created — `PomodoroTaskListProvider` and `CalendarAppQueries` — which is the outcome a
gate is for. `PomodoroTaskListProvider` was fixed properly (see below) rather than
annotated away.

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
- `schedule` on an unsupported platform now throws. Any future caller that skips
  `isSupported` fails loudly in a test rather than silently in production — which is the
  intended behaviour, not a regression to route around. `cancel`/`cancelByTask` stay
  silent and must not be made to throw.
- The `at`-based backend stays deleted. Its replacement must be `systemd --user`
  timers, keyed to this app — see
  `2026-10-06-notification-port-deleted-because-it-cancelled-other-peoples-jobs.md`.
  **Landed**, as `2026-10-07-desktop-reminders-systemd-user-timers.md`. The checklist it
  left behind was executed as written: `isSupported` flipped (to a conjunction of a
  systemd user session and a working `notify-send`, not a constant), the throw in
  `JvmReminderScheduler` now guards that narrower condition instead of covering the whole
  platform, `JvmReminderSchedulerCapabilityTest` was rewritten around both states rather
  than having its assertion deleted, and the registry row moved from `stub` to `real`.
- Three false KDoc claims were corrected. Where a file documents the absence of a
  dependency, it now says so explicitly rather than naming a class that does not exist.

## A second instance found by the new rule

The `gate` rules failed on `PomodoroTaskListProvider`, and the finding was real rather
than cosmetic: `JvmPomodoroTaskListProvider` returns an empty never-updating list while
Android observes the real Inbox, and `PomodoroScreen` renders no chips at all for an
empty list. A Desktop user saw a working screen with an empty picker and concluded they
had no tasks — the same "platform limitation presented as a user fact" defect as the
reminders, arrived at independently.

Fixed in the same shape rather than annotated away: the provider gained `isSupported`
(defaulting to `true` so the `fun interface` stays implementable in one method), the JVM
binding reports `false`, and the screen says so with a test tag instead of silently
hiding the picker.

The lesson is that this defect class is not rare or particular to reminders. The
companion detector `scripts/check-dead-settings.py` hunts the settings-shaped variant —
a value that is collected, displayed, and read by no feature — and `reminderDefault` is
its live instance. That script needed three revisions before it stopped reporting 40+
false positives, which is worth recording: each failure mode was a wrong idea about what
counts as consumption, not a bug in the matching.

1. Matching the preference key name — every key is read by the repository that writes it,
   which is a tautology.
2. Excluding the DI layer wholesale — but the DI layer is precisely where the
   contributor is resolved.
3. Matching the concrete `XSettingsContributor` — but the graph resolves the marker
   interface `XContributor` via `getOrNull<>()`.

It is proven with a positive control in both directions: zero findings on the current
tree, six when the six `getOrNull<…Contributor>()` calls are mutated.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/reminders/ReminderScheduler.kt`
- `shared/src/commonMain/.../reminders/RemindersUnsupportedException.kt`
- `shared/src/commonMain/.../reminders/JvmReminderScheduler.kt`
- `shared/src/commonMain/.../tasks/presentation/viewmodel/slot/TaskRemindersSlot.kt`
- `shared/src/commonMain/.../projects/presentation/screen/ProjectDetailBody.kt`
- `shared/src/commonMain/.../pomodoro/PomodoroTaskListProvider.kt`
- `shared/src/commonMain/.../pomodoro/PomodoroScreen.kt`
- `shared/src/jvmTest/.../reminders/JvmReminderSchedulerCapabilityTest.kt`
- `shared/src/jvmTest/.../arch/PlatformSeamGuardTest.kt`
- `shared/src/jvmTest/resources/platform-seams.tsv` (column `gate`)
- `scripts/check-dead-settings.py`, `scripts/ci/static-gates.sh`
- Prior: `2026-09-30-project-reminder-own-table.md`, `2026-10-06-notification-port-deleted-because-it-cancelled-other-peoples-jobs.md`