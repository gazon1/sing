# A repeating task is a series of occurrences, and a row says what it is

Issue: — · Related: `docs/decisions/2026-10-25-a-recurring-series-carries-the-rule-on-every-row.md`

Status: **accepted**
Tags: **tasks recurrence agenda ui**

## What

Two defects, one shape: **the app does not say what a row is, and a repeating task has
no visible future.**

1. **A repeating task had no future.** It was one row that moved: closing the
   occurrence stamped `completed_at` on the row, and the same operation then moved the
   row's `due_date` forward. The move never touched `completed_at` — the update writes
   `due_date` and `updated_at` and nothing else — so the row ended up **closed and dated
   in the future**. It left "Today" and came back in "Upcoming" struck through. There
   was no next occurrence anywhere: not in the agenda, not in the calendar, not in the
   search index. A user who completed "buy bread — every week" saw nothing new.

2. **No row said what it was.** The agenda built every row with `isRecurring = false`
   and `project = null`, hardcoded. So the repeat icon that already existed in the row
   had never been drawn by anything, and every agenda row that had a due date printed
   the literal "Без проекта" next to it — the project's name never reached the row at
   all.

A third defect surfaced while the design was being checked against the code, and it is
the reason the change has an entry point at all:

3. **A repeating task created from the create screen did not repeat at all.** The
   create path goes through the series planner, which clears the rule on every row it
   writes — including the single row of a one-date draft. The draft carried the rule;
   the planner dropped it on the way to the row. A task created with "repeat every
   week" came out with no rule, silently. Only the task screen's edit path ever
   produced a repeating task.

The representation was the cause. A repeating task was one row that moved, and one row
cannot show a future: it can only be somewhere else. A **series** can — and the repo
already has one: ADR `2026-10-22-task-series-materialized-copies` decided that a series
is several task rows sharing one id, with the shared fields duplicated on every row.

That ADR also closed with a clause that had to be superseded: *"a series never holds a
rule"*, because the series it described was a set of **picked** dates, and a rule on
such a row would generate occurrences nobody asked for. A recurring series is the other
kind: its dates **are** the rule's occurrences.

## The change in behaviour

- **A repeating task is a series.** The row the user set the rule on becomes the
  current occurrence; the rule's future dates become further rows of the same series,
  each carrying the rule and the same work. **Both doors that set a rule** — the create
  screen and the task screen — materialise the series.
- **The future is visible.** The materialised occurrences are ordinary tasks: they
  appear in the Upcoming agenda in the section of their own date, they can be opened,
  edited, completed and deleted on their own.
- **Closing an occurrence no longer moves it.** It stays on its own date, closed, and
  the next occurrence is a different row. The row that was closed and dated in the
  future — the defect — cannot exist any more.
- **A rule that has run out closes the occurrence and stops the series.** Nothing is
  archived: a closed occurrence is already out of the active list, and archiving a row
  the user closed would move finished work into the trash.
- **The window is a pure function of the rule and the series' position.** It does not
  move on its own; no scheduler is involved and no read writes.
- **The series rotates at the ceiling.** At thirty rows the oldest closed occurrence
  leaves the series and stays an ordinary closed task, so a daily task keeps recurring
  instead of stopping at thirty. The occurrence that leaves loses the rule with the
  series id — a closed row that kept its rule and lost its series id is the shape the
  launch repair adopts into a new series, so the rule that stayed would make the ceiling
  a loop.
- **Existing repeating tasks are repaired at launch.** They become series, with their
  window filled, and the row the old roll-forward left closed with a future date is
  reopened — because that row is the current occurrence.
- **A row says that it repeats.** The badge is derived from the task's own rule, and
  it shows even when the row has nothing else to say.
- **A row names its project.** The badge carries the project's name and the project's
  colour, and the "Без проекта" placeholder leaves the row.
- **An occurrence that leaves a recurring series loses the rule.** It stays an ordinary
  task, and the rest of the series keeps repeating. The row that stays behind when the
  series ends loses it too — a rule on a row that stands alone is a promise nothing
  keeps, and the shape the launch repair adopts into a new series.
- **Two kinds are not joined into one series.** The app can join two series; a recurring
  series and a series of picked dates are refused, and so are two recurring series whose
  rules differ. A join of two series of the same kind is unchanged.

## The series model it leans on

The repo already has a series: several task rows sharing one id, with the shared fields
duplicated on every row (ADR `2026-10-22-task-series-materialized-copies`). That ADR
also said a series never holds a rule — true of the series it described, a set of
**picked** dates, and false of the one needed here.

A series therefore has two kinds, and the kind is decided by where its dates come from:
a **picked-dates series** (the user picked them, no rule) and a **recurring series**
(the dates are the rule's occurrences, the rule is on every row). They never mix. The
capability `task-series` owns this model, so the change carries a delta for it as well
as for `tasks` — the refusal of a series of more than thirty dates stays for picked
dates and becomes a rotation for a recurring one.


## What is not in this change

- **The general per-row badge slot is not filled.** The agenda computes a per-row badge
  — `Overdue`, `Pinned`, `Completed`, `NoDate`, `Blocked` — and never renders it. That
  is a separate concern; this change draws the repeat and project badges in the row
  itself.
- **The task detail screen is not touched.** The badges live in the list row; the
  detail screen keeps its editable attribute rows.
- **The recurrence rule set is unchanged.** The language of rules, the units and the
  end condition stay as they are.
- **The calendar is not special-cased.** The export keeps its "one row — one event"
  rule, so a recurring series appears as one event per open occurrence — the same rule
  a multi-date series already follows. Whether a recurring series should ever be
  exported as a single event with a rule is a calendar decision and is not taken here.
- **The cloud sync format is unchanged.** `seriesId` is already in the allowlist and in
  the backup format; a recurring series uses them as they are. What a detach writes is
  the row's own fields, and the row that leaves a recurring series has no rule — so the
  document carries `seriesId: null` together with `recurrence: null`, two claims that
  travel together. The device on the other side clears both, and the row does not repeat
  there either.

## Why now

Because the defect is in the representation, and the representation is one decision
away from being right. The repo already stores a series as materialised copies, so
the work is a rule in the right place rather than a new table — and the cost of the
current arrangement is paid by every user of the repeat feature, every time they
complete a repeating task and see nothing.
