---
title: "The follow-ups from WS4, and the class of defect they share"
date: 2026-10-07
status: accepted
tags: [reminders, architecture, testing, subprocess, android, desktop]
---

## Context

The WS4 review asked for four things: tests on the launch-time re-arm, a mechanical gate
for the `at(1)` ban, a fix for the stale Room skill, and the duplicated delivery logic. All
four landed. Two further items came out of doing them.

The common thread turned out to be sharper than any individual fix: **every one of them was
a decision sitting in a place nothing could reach.**

- `rearmReminders` decides whether a Desktop reminder works once or survives a reboot. It
  was a private function in `main.kt`, an entry point no test executes.
- `handlePomodoroPhaseEnd` turns a phase into a title, a body and a tag. It had **no** test,
  and testing it in place would have meant Robolectric — whose JUnit 4 / Vintage stack is
  not wired, and whose `@Tag` does not map to Platform tags, so such tests are silently
  skipped.
- The `at(1)` ban existed in two ADRs and in nothing else.
- `AndroidNotifier.isSupported` was `= true` with a comment saying "the only question is the
  user's grant" — and it answered `true` without asking.

## Decision

### 1. `JvmReminderRearm` — the launch policy, out of `main.kt`

Which reminders get armed, in what order, and what happens to one that fails. The property
that matters is tested: **one failed `systemd-run` must not abandon every later reminder**,
because `schedule` throws by design and an uncaught throw in a loop turns one bad unit into
every subsequent reminder silently unarmed.

### 2. `AlarmHandler` in commonMain, and `AlarmReceiver` as an adapter

Not a testability shim — a correction about where the decisions live. Everything in
`AlarmHandler` has a failure mode and none of it needs a broadcast:

- a pomodoro phase becoming a notification, previously untested and untestable in place;
- the catch-up firing past-due **before** re-arming, which is what stops a just-fired
  one-shot from being re-armed in the same pass and firing forever.

What stays in `AlarmReceiver` is the `Intent` parsing and `goAsync()` — the parts only a
receiver can supply, and the parts with no decisions in them.

`androidHostTest` holds no tests and declares no Robolectric stack, so this was the only
route to local coverage. Paying to un-skip it is a larger change than the thing under test.

### 3. `SubprocessConfinedTest` — one place spawns a process

The SIGPIPE defect shipped twice in `Subprocess`'s predecessors because the only subprocess
test in the repository covered a default every other test replaced. Confining spawning to
`core/process/Subprocess.kt` makes `SubprocessTest` cover all call sites structurally rather
than by asking the next person to remember.

`JvmSecureStorage` was migrated for this, and two of its four methods had a latent
deadlock: `waitFor()` with no drain blocks the moment a child writes more than 64 KiB. It
works today because `which` writes twenty bytes, and it would hang on a machine where
`secret-tool` was unusually chatty — which is not a bug anyone debugs quickly.
`Subprocess.runCapturing` drains, folds stderr in, and takes the secret on stdin so it
never reaches a command line.

### 4. `AndroidNotifier.isSupported` measures now

It is a getter over `POST_NOTIFICATIONS`, not a constant. On API 33+ with notifications
denied, `post` returned immediately while the flag said `true` — so `ReminderDelivery`
posted, reported `Posted`, and the user saw nothing. A capability flag that lies about its
own subject is the defect those flags were introduced to prevent.

A getter rather than a `val`: the grant can be revoked from settings while the app runs,
and a captured value would answer with the permission the app *started* with.

### 5. Cross-profile re-arm, with the hole recorded

Both the Android catch-up and the Desktop re-arm armed from the profile-scoped
`observeAll()`. A reminder belonging to a profile that was not active therefore never fired
— the row existed, the UI said it was set, and nothing reminded anyone until its owner
switched profiles and relaunched.

Arming OS alarms is a device-wide operation driven by a device-wide event. Scoping it to
whoever was logged in at that moment is the bug, not a safety property. Both platforms now
arm from `observeAllProfiles()`.

That is the first query in `task_reminders` with no `user_id` filter, so
`CrossProfileReadRegistry` records it — the read-side counterpart to
`CrossUserWriteRegistry`, which exists because a query that silently ignores `user_id` is
invisible in a diff.

### 6. The Desktop fire order, split out of `main.kt` and out of the graph

`main.kt` resolved the profile and then fired the reminder, in three lines, with the reason
written as a comment. Fire first and `ReminderDelivery` — scoped to the active profile —
returns `NotFound` for a row that exists, from a process that exits 0. The user's reminder
never appears and the only evidence says the reminder does not exist.

`JvmReminderFireRunner` holds that order, and holds it as `fireAfterProfileResolution`, two
lambdas and a call. The Koin wiring stayed in `run`, which `run` is required to go through.

Taking a `Koin` in the tested function would have been the wrong call twice over: a full
desktop graph in `jvmTest` needs a filesystem and a database to assert a sequence of two
suspend calls, and a stand-in graph would only re-assert the stand-in's own construction —
which is precisely how this defect survived its first review.

## Consequences

- The Desktop re-arm, the Android catch-up and the Desktop fire path are all covered, and
  the Android one is covered from `commonTest` rather than from a source set that silently
  skips.
- Spawning a process outside `core/process/Subprocess.kt` now fails the build, so the
  subprocess runner cannot regress into being untested again.
- A denied `POST_NOTIFICATIONS` produces `Outcome.NoNotifier` instead of a delivered-looking
  log line and no notification.
- Another profile's reminders now fire without its owner having to switch to it.
- The Desktop fire order is asserted by a test, and the test costs two lambdas.
- `AlarmReceiver` is a third of its former size and holds nothing testable.
- `detekt-rules-module.yml` stopped being a problem without me: commit `f696c4d7` fixed
  the generator. Worth recording, because the last two times I checked it was red and I
  declined to regenerate a file another branch was mid-edit on.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/reminders/AlarmHandler.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/reminders/ReminderDelivery.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/reminders/data/CrossProfileReadRegistry.kt`
- `shared/src/jvmMain/kotlin/com/singularity/todo/core/process/Subprocess.kt`
- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/reminders/JvmReminderRearm.kt`
- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/reminders/JvmReminderFireRunner.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/reminders/JvmReminderFireRunnerTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/SubprocessConfinedTest.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/reminders/AlarmHandlerTest.kt`
- Prior: `2026-10-07-closing-child-streams-sends-sigpipe.md`,
  `2026-10-07-three-copies-of-reminder-delivery.md`