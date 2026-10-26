# Contract: The Plans Timeline Screen

**Feature**: `specs/016-plans-timeline-projects-menu`
**Date**: 2026-10-26

What the Plans tab promises to the user, and what a change to it may and may not alter.

---

## Shape

The screen is a list of sections. Each section has a header that names its period, and the open
tasks due inside that period. The sections run from the oldest period to the newest, and the
overdue section, when there is one, is first.

```text
┌─────────────────────────────────┐
│ Overdue                  2  +  │
│   ▸ Task that was due Monday    │
│   ▸ Task that was due last week │
├─────────────────────────────────┤
│ Today                    1  +  │
│   ▸ Task due today              │
├─────────────────────────────────┤
│ Tomorrow                0  +  │   ← a section with no task is not rendered
├─────────────────────────────────┤
│ Fri, 12 Sep              3  +  │
│   ▸ Task due that Friday        │
│   ▸ Task due that Friday        │
│   ▸ Task due that Friday        │
└─────────────────────────────────┘
```

**The granularity control** sits with the screen's title. It names the granularity in effect —
`Day`, `Week` or `Month` — and choosing another one re-groups the list in place. The screen keeps
no other mode: there is no filter, no search and no saved view.

**The period labels** are:

| Granularity | Label |
|---|---|
| Day | `Today`, `Tomorrow`, otherwise the weekday and the date — `Fri, 12 Sep` |
| Week | The date range of the week — `12–18 Sep` |
| Month | The month and the year — `September 2026` |

**The add control** on a section header creates a task due on that section's period: the day
itself in `Day`, the first day of the period in `Week` and `Month`. The screen's own action button
creates a task due today.

**The empty state** says that there is nothing scheduled. It is not a blank list.

---

## Guarantees

1. **One task, one section.** A task is rendered in exactly one section of the timeline. The
   periods are disjoint, so this holds by construction rather than by a rule someone has to
   remember.
2. **One task, one row.** The row key is the section and the task together. A mistake that put a
   task in two sections would render it twice; it would not crash the screen.
3. **Open work only.** A completed task is not on the timeline, and leaves its section as soon as
   it is marked done.
4. **Chronological.** Sections run from the oldest period to the newest.
5. **Empty periods are not sections.** A day with no tasks has no header, and the days that do
   have tasks keep their order across the gap.
6. **The horizon is the granularity's.** `Day` covers thirty days, `Week` twelve weeks, `Month`
   twelve months, counted from today.
7. **The choice survives.** The granularity in effect when the user leaves is the granularity the
   screen opens in next time.
8. **Overdue leads.** Tasks due before today are in the overdue section, first, and in no dated
   section — including when the dated period they fall in is the current week or
   the current month, which start before today.
9. **Undated work is not here.** A task with no due date has no period to belong to, and is not on
   the timeline.
10. **The actions reach the real screens.** Tapping a row opens that task's screen. The add
    controls open the task editor with the date prefilled. None of them leads to a placeholder.

---

## Producers

- The **grouper** produces the sections. It is a pure function of the tasks, the granularity and
  today's date, and it is where every guarantee above is established.
- The **tasks** come from the task repository. The whole list is read, and the horizon decides what
  of it the sections hold — a range query would make the blocking rule answer a different question
  than it answers in the agenda.
- The **granularity** comes from the profile's settings.

## Consumers

- The **Plans tab** renders it.
- The **task editor** receives the prefilled date from the add controls.
- The **task screen** is opened by tapping a row.

---

## Rejected alternatives

- **A saved timeline view.** The sections are a function of the calendar. Storing them would store
  yesterday's answer to a question about tomorrow.
- **Filters and search on the timeline.** The screen answers one question — what is ahead. A
  second question belongs to the agenda, which already has saved views for it.
- **Showing completed tasks.** The screen shows what is still ahead; a finished task is not ahead
  of anything.
- **A calendar grid.** The reference for this screen is the list of days, not the month view. The
  Calendar screen is one menu entry away and still does that job.
