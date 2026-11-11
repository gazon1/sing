---
title: "A narrow week column shows markers and opens the day; a wide one keeps title chips"
date: 2026-11-11
status: accepted
tags: [calendar, ui, layout, responsive, accessibility]
---

## Context

At 360 dp the week grid reserves 56 dp for the time gutter and gives the rest to
seven day columns: `(360 − 56) / 7 ≈ 43.4 dp`. The 2026-11-09 comparison found
three failures at that width: weekday labels broke into single letters, all-day
title chips ellipsized to two or three characters, and the `+N more` label
wrapped.

The code confirmed the pressure rather than the estimate alone:

- `DayHeaderCell` drew a 22 dp day badge, a 6 dp spacer and a 14 sp weekday label
  side by side. The badge and label together exceed 43.4 dp, so the label had
  nowhere to go.
- `TaskChip` spent 12 dp on horizontal padding and up to ~20 dp on a status,
  recurrence, emoji or series marker before the title. At 43.4 dp the title had
  almost no room.
- `TaskChip(compact = true)` existed, but it applied `Modifier.touchTarget()`
  before `size(8.dp)`. The helper reserves 48 dp of layout space, which is wider
  than the column: the reserved slot spilled into the neighbouring day.
- `TimeGridView.AllDayCell` passed `onClick = null` to `MoreTasksLabel`, so the
  tasks it hid had no route out of the cell.
- The 2026-09-30 dead-affordances ADR justified that null by saying the all-day
  cell belongs to a day the user is already viewing. That is false in
  WEEK/FOUR_DAYS: a date column is one column of a multi-day grid, and
  `DayClicked` does not enter DAY from those modes. This decision corrects that
  paragraph; it does not reopen the rest of that ADR.

The responsive decision therefore had to answer two questions together: what a
narrow column renders, and how a collapsed event is reached.

## Idea

Two candidates were compared, as the issue required.

1. **Compact markers below a measured width, title chips above it.** The grid
   keeps its column geometry. Every day still shows a density signal (a dot per
   all-day task), and a date header/overflow action opens the complete list in
   DAY view — which is now where the full list actually lives.
2. **A horizontally scrollable all-day strip.** It would preserve full titles,
   but it breaks the one thing a week grid promises: seven dates aligned over the
   same seven columns as the hour grid. The all-day strip would scroll
   independently of the day headers and the hour columns, so an event would no
   longer sit under its date. That is a worse defect than an unreadable fragment,
   and it does not help the weekday header at all.

The measured widths (from the laid-out Compose tree, not from the formula):

| Window | View | Measured day column |
|---|---|---|
| 360 dp | Week | ~43.4 dp |
| 540 dp | Week | ~69.1 dp |
| 570 dp | Week | ~73.4 dp |
| 600 dp | Week | ~77.7 dp |
| 360 dp | 4 days | ~76 dp |

DAY view is one column that takes the whole width left by the gutter; the
single-day test renders it and checks every task is shown.

The evidence is pinned by
`TimeGridNarrowTest`: the column width is read from the laid-out cell, and the
compact marker's bounds are compared against that cell, so a reserved box that
spills into the next day fails the test.

## Decision

1. **The breakpoint is 72 dp of available day-column width**, measured as
   `(grid width − 56 dp time gutter) / number of days`. At and above it, all-day
   tasks render as title chips; below it they render as compact markers.
   72 dp is the width at which 12 dp of chip padding and up to ~20 dp of leading
   icon leave roughly 40 dp — about five characters — for the title. Below that
   the chip is a fragment, not a title.

2. **A narrow week column shows up to two task markers, each a full-column
   width by 48 dp target.** The visual is an 8 dp dot; the target is the column's
   width by the project's 48 dp minimum. A 48 dp-wide target does not fit a
   ~43 dp column, and a shorter one fails the touch-target gate. Two rows cost
   about 96 dp; the rest sit behind a `+N` label. The markers stay task controls,
   so per-task navigation is not lost. Month cells keep the existing 8 dp dot in
   a 48 dp box unchanged: at ~51 dp the square fits.

3. **The date header and the overflow label open that date in DAY view.** A new
   `CalendarIntent.OpenDay(date)` sets `viewMode = DAY` and `anchor = date`. It
   is a distinct intent from `DayClicked`, which keeps its existing rule (enter
   DAY only from MONTH), because silently changing `DayClicked` would alter
   month-grid taps and date-selection.
   In the single-day view the header is not clickable (the column already is the
   day view) and no overflow label appears.

4. **The single-day view renders every all-day task, capped in height and
   scrollable.** The week strip still caps at two markers (narrow) or three chips
   (wide) plus an overflow label, but DAY view no longer caps. It bounds the
   strip at 160 dp and scrolls, so a day with many tasks neither clips the last
   ones nor pushes the hour grid off the screen.

5. **The narrow header stacks the day number over the weekday.** Both stay one
   line (`maxLines = 1`, `softWrap = false`). At wide widths the original
   number-then-weekday row is unchanged.

6. **A horizontally scrollable strip is rejected.** It breaks date-to-column
   alignment and does not solve the header.

## Rationale

**Why markers win over the strip.** The week grid's value is the shared
seven-column geometry between the header, the all-day strip and the hour grid.
A scrollable strip makes the three disagree. The marker keeps the geometry and
tells the user there are events; DAY view is the place that has room to say what
they are.

**Why the day-open route is part of the decision, not a follow-up.** Markers
alone are a lossy replacement for titles. Reachability is the requirement that
makes them acceptable: a hidden task is one tap from the date header or the
overflow count, and DAY view shows all of them. A count label that only displays
text would not satisfy this.

**Why 72 dp and not the formula's 43.4 dp.** 43.4 dp is a measurement of the
broken case, not a design breakpoint. The breakpoint is the width at which a
chip still has a title; 72 dp derives from the chip's own padding and decoration,
and the tests verify both sides of it (69.1 dp compact, 73.4 dp chip) and the
two real widths (360 dp, 600 dp).

**Why the compact marker is not reused unchanged.** The issue asked for the
measured limitation rather than an assumption. The limitation is the 48 dp
layout reservation, which is wider than the ~43 dp column it must fit. The
adaptation keeps the dot but makes its row the column's width and 48 dp tall:
that fits without spilling, meets the touch-target minimum, and leaves the
marker a task control. The cost is height, so only two are shown; the rest are
reachable through the `+N` label and the day view. Making the cell itself the
day action was rejected because it took per-task navigation away from every
marker in the column.

## Consequences

- Week at 360 dp: compact markers, a two-line header that never wraps mid-word,
  and `+N` (single-line) overflow. Week at 600 dp: unchanged title chips.
  FOUR_DAYS at 360 dp (~76 dp) keeps title chips.
- `TaskChip(compact = true)` is unchanged for the month grid. The week grid
  gets a `NarrowTaskMarker`: a full-column-width, 48 dp-tall task target with
  the same dot and the chip's colour.
- `TimeGridView` gains a nullable `onDayClick` (the header and `+N` route to
  `OpenDay`). The single-day strip is capped at 160 dp and scrolls.
- `MoreTasksLabel` gains `compact` and is single-line everywhere.
- Android at 360×800 dp was not reproduced on an emulator in this change; the
  measurement uses the same shared composables in the desktop Compose harness.
  That is the remaining verification gap before closing the tablet/phone
  screenshot criterion.
- #2737 implements the rest of the acceptance criteria (Maestro/screenshot
  coverage, Russian locale flows) on top of this decision.

## Links

- Issue [#2729](https://github.com/gazon1/sing/issues/2729) — this research
- Issue [#2737](https://github.com/gazon1/sing/issues/2737) — implementation
- ADR `2026-09-30-dead-affordances-removed` — the TimeGridView paragraph this
  decision corrects
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/calendar/presentation/components/calendar/TimeGridView.kt`
- `.../calendar/TaskChip.kt`
- `.../calendar/domain/state/CalendarIntent.kt` — `OpenDay`
- `.../calendar/presentation/viewmodel/CalendarViewModel.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/calendar/presentation/components/calendar/TimeGridNarrowTest.kt`
