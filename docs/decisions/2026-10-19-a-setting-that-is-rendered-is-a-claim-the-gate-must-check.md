---
title: "A setting that is rendered is a claim, and the app has to keep it"
date: 2026-10-19
status: accepted
tags: ["settings", "agenda", "gates"]
---

## Context

The `settings read by a feature` gate is blocking. It reports a persisted
setting as dead when no file outside the settings plumbing names it — and the
settings plumbing is the chain that *passes a value along*: the store that
assembles the section, the contributor that forwards it, the repository that
owns the flow, the screen that renders it.

Two of the fields it reported were a defect of its own:

- `WorkSchedule.weekendSat` and `WorkSchedule.weekendSun` are persisted,
  rendered under a section headed **Working Days**, and read by nothing. The
  work-day agenda — morning work, lunch, afternoon work — was shown on a
  Saturday the user had marked as *not* a working day. `AgendaPresets.workDayPreset`
  names the gap in its own KDoc: "Weekend handling ([weekendSat], [weekendSun])
  is the caller's responsibility", and the caller never took it.
- `Notifications.sound` and `Notifications.vibration` turned out to be read and
  applied by `AndroidNotifier` — the gate could not see it, because the
  exclusion covered whole domain directories. That half of the problem is fixed
  in `fix/three-red-gates` (PR #741): the exclusion is now by file, so the
  chain is hidden and the consumer is not.

This ADR records the half that is a product decision rather than a gate
mechanism: what the two working-day switches mean, and where the decision
lives.

## Idea

A setting the user can change in Settings is a claim the app makes to the
user. The claim is either true or it is a defect. "Read by nobody outside the
settings plumbing" is a description of the second.

## Decision

1. The working-day decision moves into one pure function,
   `AgendaPresets.forToday(dayOfWeek, saturdayIsWorkingDay, sundayIsWorkingDay, workDay, offDay)`.
   The Today route supplies the day of week and the two switches and renders
   the result. The function is the "caller's responsibility" the preset
   documents, and it is testable without a device.

2. The switch semantics follow the **Settings screen**, not the field names:
   the section is headed "Working Days", so `weekendSat = true` means
   *Saturday is a working day*. The persisted name reads the other way round,
   and the screen is what the user was shown. The KDoc on `forToday` says so,
   because the next reader will meet the name first.

3. On a working day the work-day preset applies; on a day off the plain
   date-based preset does, so a Saturday that is not a working day shows the
   date sections instead of morning/lunch/afternoon work blocks.

## Rationale

The decision belongs in one function because the two callers of the same
question — "is today a working day?" — must not be able to disagree, and
because a pure function is the only version of this rule that can be tested
without an emulator. The route is the only place that knows the day of week
and the user's switches, so the route calls the function; it does not
re-implement it.

The semantics were taken from the screen rather than from the field name
because the field name is a lie the screen does not tell: a user who reads
"Working Days → Saturday" and turns it on expects Saturday to be a working
day, and `weekendSat = true` is what the switch writes. Renaming the field
would be a migration; honouring the screen does not need one.

## Consequences

- A Saturday the user marked as a working day shows the work-day agenda; a
  Saturday they did not shows the plain date-based sections. The behaviour
  changes on the days the setting is about, and nowhere else.
- The two settings stop being dead: the gate sees a reader outside the
  plumbing, which is what the gate asks for.
- The switch order matters in the function's signature — the Saturday switch
  decides Saturday and the Sunday switch decides Sunday — and the test pins
  that each switch decides its own day and only its own day.
- `AgendaNavContent` grew past detekt's method-length limit, so the Today
  branch moved into its own composable. That is a shape change, not a
  behaviour one.

## Links

- `docs/decisions/2026-10-07-dead-settings-gate-must-reach-the-field.md` — the
  gate this decision answers.
- `fix/three-red-gates` (PR #741) — the gate's own half: the exclusion by
  file, the controls that could not fail, and the sync push phase's
  data-loss bug.
- `openspec/changes/archive/2026-10-20-working-day-switches-reach-the-agenda/` — proposal, tasks
  and the spec delta (`tasks` REQ "A setting that changes the agenda changes
  the agenda").
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/logic/AgendaPresets.kt`
  — `forToday`.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/nav/AgendaRouteMapping.kt`
  — the caller.
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/agenda/domain/AgendaWorkingDayTest.kt`
  — the decision, tested.
