---
title: "Three copies of reminder delivery, and the class that replaced them"
date: 2026-10-07
status: accepted
tags: [reminders, architecture, tests, android, desktop]
---

## Context

WS4 shipped Desktop reminders. In doing so it added `JvmReminderFire.fire` — a fourth
copy of the same logic in the same repository.

Counting them:

1. `AlarmReceiver.handleReminderFire` — Android, a single alarm fired.
2. `AlarmReceiver.rescheduleAll`'s boot catch-up — Android, a batch of past-due reminders.
3. `JvmReminderFire.fire` — Desktop, the `fire-reminder` launcher.
4. `AlarmManagerReminderScheduler` — Android, arming.

Copies 1–3 all do the same four steps: look up the row, read the fresh task title, compute
the text, post, delete if it was a one-shot. Every one of them was correct when written.
The KDoc on `JvmReminderFire` said so explicitly — "mirrors `handleReminderFire` step for
step, deliberately" — which is a sentence describing an invariant the compiler does not
check and no test enforces.

## Why three is the number that mattered

Two copies is a smell. Three is where the question "which one is correct in six months?"
stops having an obvious answer, because the answer becomes "whichever nobody touched".

And the steps that could drift are precisely the ones that are easy to get subtly wrong
and hard to notice:

- **The tag.** `AlarmContract.tagFor(userId, id)` scoped by the *firing* profile versus by
  the *row's owner*. Swapping them cross-contaminates notification replacement keys
  between profiles — a reminder shows twice, or one profile's reminder replaces another's.
- **Whether a failed delete changes the outcome.** One copy reported failure and one did
  not, and the user-visible consequence is a reminder that either fires repeatedly or does
  not fire at all.
- **A missing task.** `ReminderFireLogic` falls back to `"Task Reminder"`. A copy that
  re-derived the title itself would show an empty notification and no one would report it.

The Desktop copy did not get written by copying carelessly. It was written deliberately to
mirror the Android one. The problem is that "deliberate" is not a mechanism, and the third
copy arrived eight months of good intentions after the second.

## Idea

Move the four steps into common code; leave each platform with only what is genuinely its
own — *when* to fire, and the process lifecycle around it.

The alternative was to leave them and add a comment. A comment that says "these must stay
in sync" is exactly the artefact that survives until they do not.

## Decision

`ReminderDelivery` lives in `feature/reminders/ReminderDelivery.kt`, in **commonMain**, and
both platforms call it:

- `AlarmReceiver.handleReminderFire` → `delivery.fire(reminderId, userId)`
- `AlarmReceiver.rescheduleAll` catch-up → `delivery.fireKnown(reminder)`
- Desktop `main`'s `fire-reminder` path → `koin.get<ReminderDelivery>().fire(...)`

It is bound once in `CoreDiModule.kt`, a common module, so no `platform-seams.tsv` row is
needed: a row describes something that differs between platforms, and this does not.

### Why `fire` and `fireKnown` are two methods

The boot catch-up enumerates rows rather than looking each one up. Re-reading each through
`ReminderRepository.get` would filter it by the active profile — dropping reminders from a
batch that was explicitly built to be profile-complete.

That is the one behavioural difference between the entry points, and it is exactly the
kind that gets "tidied up" into a bug, so it is named in the API and pinned by a test
rather than left to be rediscovered.

### Why the tests moved to `commonTest`

`JvmReminderFireTest` covered the Desktop copy in a JVM-only test. A JVM-only test can only
ever constrain one of three implementations, which is how three copies survived a codebase
this disciplined. `ReminderDeliveryTest` is in `commonTest` because it now constrains
every caller: if Android's receiver goes back to assembling its own notification, the tests
stop saying anything true about the Android path — and that is detectable precisely because
the class is shared rather than duplicated.

### Why `ReminderFireLogic.shouldDeleteAfterFire` exists

`execute` returns an `Outcome` carrying `shouldDelete`, but `ReminderDelivery` needs the
boolean *without* a title, and the obvious spelling —
`execute(reminder, null).shouldDelete` — computes a title and a body in order to learn a
boolean. That spelling is also what three copies would each have reached for, and it puts a
fake title read one refactor away from being a real one. A named predicate removes it.

## Consequences

- One copy of the delivery steps exists. The tag, the delete-failure policy and the
  missing-task fallback are now written once.
- `AlarmReceiver` is smaller and holds only Android concerns: the broadcast lifecycle, the
  arming, and the pomodoro and boot actions.
- `ReminderDelivery` is common code with no platform knowledge, so Android gains coverage
  it did not have and Desktop gains Android's review history.
- One thing is *not* fixed by this, and the asymmetry is now the sharper problem: the boot
  catch-up and the Desktop re-arm are both scoped to the active profile, so a reminder
  belonging to another profile is armed only when that profile becomes active. Fixing that
  on Desktop alone would reintroduce exactly the platform drift this class removes, so it
  has to be done for both — and it needs a profile-independent read, which is a
  deliberate hole of the kind `CrossUserWriteRegistry` exists for. Deliberately not
  attempted here.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/reminders/ReminderDelivery.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/reminders/ReminderFireLogic.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/reminders/ReminderDeliveryTest.kt`
- `shared/src/androidMain/kotlin/com/singularity/todo/feature/alarms/AlarmReceiver.kt`
- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/reminders/JvmReminderFireCommand.kt`
- Introduced by: `2026-10-07-desktop-reminders-systemd-user-timers.md`
- Related: `feature/agenda/data/CrossUserWriteRegistry.kt` — the registry a cross-profile
  read would have to join, if one is ever written.