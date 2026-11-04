---
status: accepted
date: 2026-11-05
---

# The three agenda tabs cover a horizon, not the whole agenda

## Context

ADR `2026-10-31-the-agenda-tabs-are-disjoint` turned Inbox, Today and Upcoming
into a partition: each tab owns a set of date buckets, no bucket is owned twice.
That partition left a hole the ADR mentioned only as a consequence — a task
dated further out than next week is in **no** bottom-bar tab. `Inbox` holds the
undated tasks, `Today` holds Overdue and Today, `Upcoming` holds Tomorrow, the
rest of this week and next week. A task due in three weeks is none of those.

Nothing was lost: the task is still in the Plans timeline, in the Calendar, and
in a saved view seeded from `AgendaPresets.All`. But nothing said so either. The
next reader of the presets, or of the tab, could not tell whether the gap was
the decision or an oversight — and a user who lives in the bottom bar had no
pointer to where a later task is seen.

## Idea

Either the Upcoming tab grows a "Later" section that takes what falls beyond
next week, or the bar is stated to cover a horizon only, with the answer for
"what is due later" named as the Plans timeline and the Calendar.

## Decision

**The bottom bar covers a horizon.** The Upcoming tab does not grow a "Later"
section, and the three tabs stay a partition of what they cover.

A task due beyond next week is seen in exactly three places, and they are named
wherever the tab is described:

- the **Plans timeline** (`AgendaPresets.All`, the full list, drawn on a
  timeline rather than grouped into bottom-bar buckets),
- the **Calendar** (`AgendaPresets.byDateRange`, the month the user is looking
  at),
- a **saved view seeded from `AgendaPresets.All`**, which holds every section
  the tabs show plus the buckets they leave out.

The three tabs keep answering their three questions — "what is not dated yet",
"what is now or late", "what is coming soon" — and "what is due later" is a
timeline question, not a tab question.

`AgendaTabDisjointnessTest` keeps the assertion that a task due beyond next week
is in no tab, states why, and additionally pins that the wide view still holds
it — so the gap cannot be read as a bug and the task cannot quietly fall out of
the agenda altogether.

## Rationale

A "Later" section has no bucket to sit on. The relative buckets stop at
`NextMonth`, and `ThisMonth` / `NextMonth` are month-shaped: a section built
from them would either miss the last days of the current month or re-show tasks
that "Next Week" already owns. "Beyond next week" is an open-ended range, and
expressing it needs the evaluation date at definition time — the same reason the
disjointness change used `Selector.Not` rather than `DateRange`. Adding it would
mean new bucket arithmetic, which is the very thing the tab contract is pinned
against.

The alternative — a catch-all section on the tab — would make Upcoming the wide
view under a tab name. That is what the disjointness change removed: three tabs
that render the same rows under three names.

The horizon option also keeps the tab's promise honest. "Upcoming" says what is
coming soon; a task three weeks out is planned, not coming up.

## Consequences

- Positive: the gap is a stated decision, with one place that explains it, so a
  reader of the presets, of the test, or of the tab documentation finds the
  answer instead of inferring an oversight.
- Positive: the wide view stays the only place that holds every task, so a saved
  view started from it is still a complete list.
- Positive: the Upcoming tab says the pointer itself — a muted line at the end of
  its content, and below its empty state, names the Calendar and the Plans
  timeline. The user reads the same answer the presets' documentation gives; the
  line is `AgendaHorizonNote`, pinned by `AgendaHorizonNoteTest`.
- Negative: a user who lives in the bottom bar does not see a task dated beyond
  next week in a tab, and has to reach it through Plans, the Calendar or a saved
  view. The line says where.

## Links

- `docs/decisions/2026-10-31-the-agenda-tabs-are-disjoint.md`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/logic/AgendaPresets.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/agenda/domain/AgendaTabDisjointnessTest.kt`
- `shared-ui/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/screen/AgendaHorizonNote.kt`
- `shared-ui/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/screen/AgendaContent.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/agenda/AgendaHorizonNoteTest.kt`
