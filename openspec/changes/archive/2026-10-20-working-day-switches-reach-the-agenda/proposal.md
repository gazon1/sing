# working-day-switches-reach-the-agenda

Issue: — · Related: `docs/decisions/2026-10-19-a-setting-that-is-rendered-is-a-claim-the-gate-must-check.md`,
`docs/decisions/2026-10-07-dead-settings-gate-must-reach-the-field.md`

## What

Make the two "Working Days" switches in Settings reach the agenda they
describe.

`WorkScheduleSettingsScreen` renders "Saturday" and "Sunday" under a section
headed **Working Days**; `WorkScheduleSettingsRepository` persists them;
nothing read them. The work-day agenda — the morning/lunch/afternoon blocks —
was applied on a Saturday the user had marked as *not* a working day.
`AgendaPresets.workDayPreset` names the gap in its own KDoc: "Weekend
handling ([weekendSat], [weekendSun]) is the caller's responsibility", and the
caller never took it.

Concretely:

1. `AgendaPresets.forToday` decides whether the work-day preset applies, from
   the day of week and the two switches. The Today route supplies the day of
   week and the switches, and renders the result.
2. The decision is one pure function, so the route cannot re-implement it and
   the behaviour can be tested without an emulator.
3. The switch semantics follow the Settings **screen**, not the persisted field
   names: the section is headed "Working Days", so `weekendSat = true` means
   Saturday *is* a working day.

## What is not in this change

The gate half of the same finding — the dead-settings gate excluding whole
domain directories, and therefore reporting `Notifications.sound` and
`Notifications.vibration` as dead although `AndroidNotifier` reads and applies
them — is fixed in `fix/three-red-gates` (PR #741), together with two controls
that could not fail and the sync push phase's data-loss bug. This change owns
the product behaviour; that branch owns the gate.

## What not to do

- **Do not baseline the settings.** A baseline line is a claim that a human
  chose not to act. "The switch is rendered and does nothing" is not a claim
  worth making.
- **Do not delete the switches from the Settings screen.** The user can turn
  "Saturday" on today; the switch persists; and the agenda ignores it. That is
  the defect.
- **Do not let the route decide on its own.** A second implementation of "is
  today a working day?" is how the two would drift apart.

## Consequences

- A Saturday the user marked as a working day shows the work-day agenda; a
  Saturday they did not shows the plain date-based sections.
- The two settings stop being dead: the gate sees a reader outside the
  settings plumbing, which is what the gate asks for.
- The Today branch of the agenda route moved into its own composable, because
  it no longer fits detekt's method-length limit.
