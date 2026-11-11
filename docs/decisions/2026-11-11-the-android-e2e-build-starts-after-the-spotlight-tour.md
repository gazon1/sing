---
title: "The Android E2E build starts as a user who has already seen the spotlight tour"
date: 2026-11-11
status: accepted
tags: ["ui", "compose", "tests", "onboarding", "maestro"]
---

# The Android E2E build starts as a user who has already seen the spotlight tour

## Context

Issue [#2819](https://github.com/gazon1/sing/issues/2819): "maestro: the seeded
row never arrives in CI, so 41 flows share one cause".

The probe says which of the three candidates it is, and it is the third one:
**the row arrived and the screen did not render it**.

- *The deep link did not open* — excluded. The CI logcat carries
  `ActivityTaskManager: START u0 {act=android.intent.action.VIEW
  dat=todo-debug://seed/... cmp=com.singularity.todo/.debug.DebugSeedActivity}`
  and `I/DebugSeedActivity: task 'Buy milk' was Created`.
- *The seed wrote to another session or profile* — excluded. The device database
  after the failing run holds exactly one row — `('Buy milk',
  user_id='00000000-0000-0000-0000-000000000001', due_date=NULL,
  archived_at=NULL, completed_at=NULL)` — in the default `Personal` profile, and
  the row is in `tasks_fts`. That is the row the flow asked for, in the session
  the flow is looking at.
- *The row arrived and the screen did not render it* — this is what happened. The
  failing screen-hierarchy dumps show the agenda shell plus the **first-run
  onboarding spotlight card** ("Saved views", "1 of 2") and no agenda content at
  all; a passing run's dump has the greeting, the section and the row, and no
  spotlight card.

The mechanism is in `SpotlightOverlay.kt`: after the scrim, the overlay draws a
`Box(Modifier.fillMaxSize().clickable { absorbScrimTap() })`. While the tour is
up it swallows every tap on the screen below it, and `mergeDescendantSemantics`
prunes the agenda subtree out of the accessibility tree. `Maestro/helpers/seed-task.yaml`
ends with `tapOn: id: nav_tab_inbox` — that tap is absorbed, so the app stays on
the **Today** tab, where an undated task ("Buy milk" has no due date) does not
belong, and the helper's `scrollUntilVisible id: task_item_buy_milk` fails with
`No visible element found: id: task_item_buy_milk`.

An A/B probe on a physical device (RMX3938, API 35) after `pm clear` settles it:

- fresh install → seed → `tapOn nav_tab_inbox` → wait for the row → **failed**;
- fresh install → seed → `tapOn onboarding_spotlight_skip` → `tapOn nav_tab_inbox`
  → wait for the row → **the whole flow passed**.

One flow reproduces it: `Maestro/flows/tasks/10-swipe-delete-undo.yaml`. The same
key was reported by `tasks/04-delete.yaml`, `tasks/05-archive-via-menu.yaml`,
`tasks/06-delete-undo.yaml`, `archive/01-restore.yaml`, and the same first-run
state was in the dumps of `profile/02-isolation.yaml`,
`smoke/03-health-drilldown.yaml` and `tasks/subtasks/TASK-SUB-01.yaml`.

The product behaviour is not a defect: a first-run tour is supposed to cover the
screen until the person finishes it. The defect was in the test environment,
which mounted the app as a user who had never seen the tour — the same defect the
desktop harness had, fixed a day earlier by
`2026-11-09-the-desktop-harness-starts-after-the-spotlight-tour.md`.

## Idea

- Fix the test environment, not the product.
- Make the E2E build's onboarding state explicit and shared, the way the desktop
  harness's is.
- Leave the tour's own tests alone.

## Decision

- `testOnboardingModule()` (`shared/src/androidMain/.../test/stubs/`) binds
  `OnboardingSettingsRepository` to the existing
  `FakeOnboardingSettingsRepository(initialSeenVersion = SpotlightContent.VERSION)`:
  the E2E build starts as a user who has finished the tour, so no overlay covers
  the screen a flow is about. The bound record ignores `resetSpotlight()`, so
  "Show tips again" cannot put the tour back in an E2E run.
- `SingularityApp.extraKoinModules()` loads it next to `testAuthModule()` when
  `TEST_AUTH_BYPASS` is set. Both are loaded after `startKoin` with
  `allowOverride = true`, so the onboarding binding wins last-definition-wins over
  the DataStore-backed one in `CoreDiModule` — the convention the desktop harness
  already uses.
- The tour's own tests are untouched: they bring their own record.

## Rationale

**The product is right and the environment was wrong.** Making the tour
non-modal, or letting taps through the scrim, would change a deliberate first-run
experience to satisfy a test environment, and would need its own decision record.

**The Android E2E build is the Android harness.** The desktop harness mounts the
whole production app in a test environment, so it needed a test-environment fix;
the Android E2E build is the same thing for the phone, and it now starts the same
way. One fake, two harnesses.

**A Maestro-side dismissal was considered and rejected.** `tapOn` has no
`optional` flag in Maestro 2.10, so a dismissal that is conditional on the card
being on screen needs a wait for the card — and while the tour is up, the agenda
is not in the accessibility tree, so the first flow of a run would still be
measured in the wrong state. The app-side binding has no race: the tour is never
eligible.

**Replaying the tour in an E2E build is not desirable.** "Show tips again" resets
the record and asks the tour to appear; the fake does both, and the DataStore-backed
record does both. In an E2E run that would put the first-run scrim back over the
screen the flow is about, and the scrim is what prunes the agenda out of the
accessibility tree — the failure this decision exists to remove, one settings tap
later. The E2E record therefore ignores `resetSpotlight()`: the tour is not on
screen in an E2E build, and it does not come back on demand. The release build is
untouched.

## Consequences

- The E2E build never shows the first-run tour, and it does not come back on
  demand: "Show tips again" is inert there. A flow that wants the tour has to
  bring its own record, exactly as in the desktop harness.
- The debug build carries the same behaviour, because the debug build is the E2E
  build (`TEST_AUTH_BYPASS` defaults to on there). A developer who wants the
  tour as a user sees it builds without the bypass (`-PTEST_AUTH_BYPASS=false`).
- The binding is guarded twice: `TestOnboardingModuleTest` (the JVM lane cannot
  reach the module, so the test lives in `androidHostTest`) proves the record
  says the tour is seen and that a reset neither clears it nor asks for a
  replay; `E2eOnboardingWiringTest` proves the application loads the module at
  all, and loads it under the `TEST_AUTH_BYPASS` condition.
- The cause is recorded in `config/docs/maestro-debt.yaml` under issue #2819, so
  a run that reports it again is a regression with an owner rather than a new
  cause nobody agreed to carry.
- The fix does not make the blocked flows pass; it makes them reach the failure
  they really have. On the device, `tasks/06-delete-undo` fails because the undo
  does not put the row back — after Delete, Undo and Back the row is still
  archived in the database — and `tasks/subtasks/TASK-SUB-01` fails because the
  add-subtask input is not composed below the fold. Both belong to the issues
  that own those flows (#2723 and #2722), not to this one.

## Links

- `shared/src/androidMain/kotlin/com/singularity/todo/test/stubs/TestOnboardingModule.android.kt`
- `shared/src/androidHostTest/kotlin/com/singularity/todo/test/stubs/TestOnboardingModuleTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/E2eOnboardingWiringTest.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeRepositories.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/onboarding/SpotlightOverlay.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/onboarding/SpotlightTourController.kt`
- `androidApp/src/main/kotlin/com/singularity/todo/SingularityApp.kt`
- `Maestro/helpers/seed-task.yaml`
- `Maestro/flows/tasks/10-swipe-delete-undo.yaml`
- `docs/decisions/2026-11-09-the-desktop-harness-starts-after-the-spotlight-tour.md`
