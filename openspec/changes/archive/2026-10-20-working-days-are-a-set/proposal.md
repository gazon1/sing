# working-days-are-a-set

Issue: — · Related: `docs/decisions/2026-10-19-the-working-week-is-a-set-of-days.md`,
`docs/decisions/2026-10-19-a-setting-that-is-rendered-is-a-claim-the-gate-must-check.md`

## What

Make the **Working Days** section mean what its heading says: the set of days
the user works on.

The section holds two switches today — *Saturday* and *Sunday* — persisted as
`WorkSchedule.weekendSat` and `WorkSchedule.weekendSun`, and read by nobody.
Two switches named after the weekend can only say something about the weekend;
the section promised a working week and delivered a weekend. The dead-settings
gate reported the two fields, and the baseline waived them, because removing
them would "delete persisted user preferences and drop two Settings controls,
which is a product decision and not a gate repair".

This change takes the product decision, and it takes it in the shape the
question deserves:

1. `WorkSchedule.workingDays: Set<DayOfWeek>` replaces the two booleans.
2. The screen renders seven switches, Monday to Sunday, each with its own test
   tag. The intent carries the whole set, so a toggle cannot lose a concurrent
   toggle.
3. The agenda asks the same question the section asks: is today in the set?
   `AgendaPresets.forToday` owns the answer; the route supplies the set.
4. The two legacy preferences are not lost: while the set has never been
   written, the set is derived from them, read the way the *screen* reads
   them — `true` means the day *is* a working day. The first write makes them
   inert.
5. The settings export carries the set next to the two derived flags, so a
   file travels in both directions.

## What not to do

- **Do not baseline the settings.** A baseline line is a claim that a human
  chose not to act. "The switch is rendered and does nothing" is not a claim
  worth making — and this change removes the pair instead.
- **Do not follow the field names in the migration.** `weekendSat = true` means
  Saturday *is* a working day. The screen is the authority on what a switch
  means; the name is the accident. A migration that inverted the meaning would
  move every weekend worker to the wrong week.
- **Do not let the route decide on its own.** The decision stays in one pure
  function.
- **Do not collapse "never chosen" into "chosen nothing".** An absent
  preference is answered by the legacy booleans; a stored empty set is a user
  who turned every day off.

## Consequences

- A user whose working week is not "Monday to Friday, maybe the weekend" can
  say so, and the agenda honours it.
- The dead-settings waiver disappears: the fields are gone, so there is
  nothing to waive.
- `SettingsSection.WorkSchedule` and `SettingsIntent.WorkSchedule` change
  shape. The settings export gains an optional field; the schema version is
  unchanged, because the old fields are still written.
