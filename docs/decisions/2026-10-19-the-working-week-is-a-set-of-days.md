---
status: accepted
date: 2026-10-19
---

# The working week is a set of days, not two flags about the weekend

## Context

The settings screen has a section headed **"Working Days"**. For a long time
that section held two switches: *Saturday* and *Sunday*. The persisted fields
were `WorkSchedule.weekendSat` and `WorkSchedule.weekendSun`.

Two switches named after the weekend can only ever say something about the
weekend. The section promised a working week and delivered a weekend. Worse,
the two fields were read by nobody: the dead-settings gate reported them, and
the baseline waived them with the note that removing them "would delete
persisted user preferences and drop two Settings controls, which is a product
decision and not a gate repair".

The waiver was honest, and it was the right way to hold a product question
open. But the question was asked in the wrong shape. "What should a weekend
flag *do* in the agenda?" invites a list of answers (dim the day? change the
greeting window? exclude it from the work schedule?), and each answer is a
special case. The real question is "which days does this user work on?" — and
that question has one answer, a set.

A second defect sat on top: the field names read the opposite way round from
the switches. `weekendSat = true` means *Saturday is a working day* — the
setting screen is the authority on what the switch means, and the field name
says the reverse. Any migration that follows the name would move every
weekend worker to the wrong week.

## Idea

Make the model what the section says it is: the set of days the user works
on. Store the set. Ask the agenda the same question the section asks.

## Decision

`WorkSchedule.workingDays: Set<DayOfWeek>` replaces the two booleans.

- **Storage.** The set is stored as its ISO day numbers (`1,2,3,4,5` — Monday
  is 1), ascending, comma separated, under `schedule.working_days`. Numbers
  rather than names, because the storage format outlives the code that writes
  it.
- **Absent versus empty.** The preference being *absent* means "this user
  never chose, ask the legacy booleans". A *stored empty string* means "this
  user chose no working days". The two must not collapse into one, and they
  do not: a user who turned every day off is a user with no working days.
- **Migration.** While the preference is absent, the set is derived from the
  two legacy booleans, read the way the *screen* reads them: `true` means the
  day is a working day. The first write of the set makes the booleans inert.
  The booleans stay in the store, so nobody loses a preference.
- **The agenda.** `AgendaPresets.forToday(dayOfWeek, workingDays, workDay,
  offDay)` asks whether the day is in the set. The route supplies the set; the
  pure function owns the decision.
- **Export and import.** The snapshot carries the set as ISO numbers, next to
  the two legacy flags *derived* from it. A file written today can still be
  read by an older app (the flags are there), and a file written before the
  set existed can still be read by this one (the flags answer for it).
- **The screen.** Seven switches, Monday to Sunday, one per day, each with its
  own test tag (`settings_working_day_monday` …). The intent carries the whole
  set: a toggle would have to read, change and write back, and two toggles
  fired together would lose one.

## Rationale

- The section's heading is a promise. A model that can express the promise
  cannot be a rendering of something else.
- One question ("is today in the set?") replaces a special case per day. A
  user whose working week is only the weekend, or who does not work on
  Wednesdays, is expressible without a new field.
- The dead-settings waiver disappears rather than being re-justified: the
  fields are gone, and the gate has nothing to waive.
- The migration follows the screen, not the field name, because the screen is
  what the user read. The name was the accident.

## Consequences

- `SettingsSection.WorkSchedule` and `SettingsIntent.WorkSchedule` change
  shape: `UpdateWeekendSat` / `UpdateWeekendSun` become
  `UpdateWorkingDays(Set<DayOfWeek>)`.
- The settings export gains a field. The schema version is unchanged, because
  the new field is optional and the old fields are still there.
- The two old preferences remain in the store, unread after the first write of
  the set. Removing them is a separate decision, and it is not this one.
- The working-day test is rewritten around the set; the meaning of the legacy
  booleans is pinned by its own test, because the migration depends on it.

## Links

- `docs/decisions/2026-10-19-a-setting-that-is-rendered-is-a-claim-the-gate-must-check.md`
  — the working-day switches reaching the agenda, which this generalises.
- `docs/decisions/2026-10-07-dead-settings-gate-must-reach-the-field.md` — the
  gate that found the two unread fields.
- `openspec/changes/working-days-are-a-set/specs/tasks/spec.md`
