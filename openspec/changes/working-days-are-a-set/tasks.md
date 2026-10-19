# Tasks — working-days-are-a-set

- [x] Add `WorkingDays`: the default week (Monday to Friday), the storage form
      (ISO day numbers, comma separated), the parser, and the projection of the
      two legacy booleans.
- [x] Replace `WorkSchedule.weekendSat` / `weekendSun` with
      `WorkSchedule.workingDays: Set<DayOfWeek>` in the repository, the store,
      the settings section and the settings intent.
- [x] Migrate rather than drop: while the preference is absent, the set is
      derived from the two legacy booleans, read the way the screen reads them.
- [x] Render seven switches, Monday to Sunday, each with its own test tag
      (`settings_working_day_<day>`); the intent carries the whole set.
- [x] Ask the agenda the same question: `AgendaPresets.forToday(dayOfWeek,
      workingDays, workDay, offDay)`, and the Today route supplies the set.
- [x] Carry the set through export and import, next to the two derived flags,
      so a file travels in both directions.
- [x] Remove the dead-settings waiver: the fields are gone, so there is
      nothing to waive.
- [x] Cover it with tests: the set's storage round-trip, the legacy reading,
      and the agenda's decision for a week without weekdays and for an empty
      week.
- [x] Record the decision: ADR
      `2026-10-19-the-working-week-is-a-set-of-days.md`.
