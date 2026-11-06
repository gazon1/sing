---
status: accepted
date: 2026-11-05
---

# The work-day plan sits inside the Today tab

## Context

The bottom bar has three agenda tabs, and they are a partition of the tasks:
Inbox holds the undated ones, Today holds Overdue + Today, Upcoming holds what
is coming (ADR `2026-10-31-the-agenda-tabs-are-disjoint`).

That partition was pinned against the three date-based presets and nothing
else. On a working day the Today route does not render `AgendaPresets.Today`:
it renders the work-day plan built from the user's work hours
(`AgendaPresets.workDayPreset`, wired by ADR
`2026-10-18-work-schedule-routing-today-screen`). The plan declared three
sections and each was a time-of-day window, so under that configuration:

- an overdue task, and a task due today with no time on it, were in **no tab at
  all** — the plan matched them in none of its windows, Inbox leaves dated
  tasks alone, and Upcoming owns the days after today;
- a task with a time was in today's plan **whatever its date** — the window
  matched `dueTime` and never looked at `dueDate`, so a 10:00 task due next
  month was listed in this morning's work.

The partition the ADR stated was therefore false for anyone who had configured
work hours, and no test could say so.

The definition also carried a title of its own ("Work Day"), which the agenda
bar draws. The destination renamed itself at the weekend, and on a working day
the bar named something the tab underneath does not (#1954).

## Decision

**The work-day plan is layered inside the Today tab; it does not replace it.**

The tab owns Overdue and Today on every day, and on a working day the work
blocks are extra sections between them:

| Section | Selector | Discards? | Prefill |
|---|---|---|---|
| Overdue | `DateBucket(Overdue)` | yes | inherited from Today |
| Work | `AllOf(DateBucket(Today), TimeOfDay(dayStart, lunchStart - 1))` | yes | none |
| Lunch | `AllOf(DateBucket(Today), TimeOfDay(lunchStart, lunchEnd))` | yes | none |
| Work | `AllOf(DateBucket(Today), TimeOfDay(lunchEnd + 1, dayEnd))` | yes | none |
| Today | `DateBucket(Today)` | no | inherited from Today |

Work/Lunch/Work set no `SectionPrefill`, so they have no inline create
affordance (the same as the previous preset's behaviour).

Three things follow from that shape, and each of them is load-bearing:

- **The work blocks are narrowed to today.** A timed task due on another day
  belongs to the tab that owns that day, not to today's plan.
- **Order is Overdue, the work blocks, then Today.** The blocks `discard` what
  they match, so a task due today at 10:00 is listed once, in the morning
  block. The date sections do not discard, so a block declared *after* "Today"
  would render nothing: "Today" would have claimed every timed task first.
- **Nothing is dropped.** A task due today outside the work hours (before the
  day starts, after it ends, or with no time at all) falls through to the
  "Today" section.

The definition is `AgendaPresets.Today` with the blocks inserted, **title
included**, so the header names the Today tab on a working day and on a day off
alike. The title has one source — the Today preset — and the destination's
header and the bottom bar cannot drift apart again.

The adapter stays where it was: `AgendaPresets.forToday` still decides *whether*
today is a working day, and the Today route still supplies the user's working
days and the configured hours. This decision changes what `workDayPreset`
returns, not when it is used.

## Rationale

The alternative — the work-day plan replaces the tab deliberately, and the
partition is restated as "the work-day configuration is its own thing" — was
rejected because it leaves the overdue and untimed tasks with no home in the
bottom bar. Those are exactly the tasks the Today tab exists for, and a user
who configures work hours has not asked to stop seeing them.

The second alternative — keeping the plan as a replacement but adding a "No
time" section — was rejected for the same reason plus one more: today's timed
tasks would still be doubled or dropped depending on the order of the sections,
and the tab would still not own its own date buckets.

## Consequences

- Positive: the three tabs are a partition on every configuration, and
  `AgendaTabDisjointnessTest` now runs its equalities under both a working day
  and a day off.
- Positive: the Today tab's header is one string, the tab's own name, on every
  day (#1954).
- Positive: a timed task due on another day no longer appears in today's plan.
  It was never intended there, and the tab that owns its day now shows it.
- Positive: the agenda lists a section by its **id**, not its display name, so
  the two blocks named "Work" no longer put two headers under one list key —
  which took the screen down with "Key ... was already used" whenever both
  blocks held a task.
- Negative: a user who configured work hours sees more on the Today tab than
  the day plan — the overdue and untimed tasks are back at the top and the
  bottom of it. That is the point of the change, but it is a visible one.
- Note: a task dated further out than next week is still in no agenda tab. That
  is the partition's own stated gap (ADR
  `2026-10-31-the-agenda-tabs-are-disjoint`), not a consequence of this one.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/logic/AgendaPresets.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/agenda/domain/AgendaTabDisjointnessTest.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/agenda/domain/AgendaWorkingDayTest.kt`
