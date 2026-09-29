---
title: "Nine calendar affordances were removed: they promised a feature that does not exist"
date: 2026-09-30
status: accepted
tags: [calendar, ui, gap, cleanup]
---

## Context

The plan's **PR-2.15 — "Calendar Filter/More icons → `Modifier.clickable` with real
intents"** assumed `CalendarIntent` already had filter and menu variants to wire to.
It does not. `CalendarIntent` is `ViewModeChanged`, `GoToday`, `GoNext`,
`GoPrevious`, `DayClicked`, `ToggleMiniCalendar`, `TaskClicked`, `DismissMiniCalendar`,
`MonthPageChanged`, `EmptyCellLongPressed` — no filter, no overflow menu.

There is also no feature behind them: no `CalendarFilterPanel` class anywhere in the
tree, no filter state on `CalendarViewModel`, and nothing in the task query that a
filter would narrow. The calendar does not filter by project, tag, priority, status or
task/note kind, and never has.

Nine rendered affordances promised all of it:

| Site | What it rendered |
|---|---|
| `CalendarTopBar.kt` | "More" `⋯` icon — no menu, no click handler |
| `CalendarTopBar.kt` | "Filter" icon + text label — no sheet, no click handler |
| `MiniCalendarPanel.kt` | 5 filter rows: Project, Tags, Priority, Tasks / Notes, Status — all `onClick = {}` |
| `MonthGridView.kt` | "+N more" — `onClick = {}` |
| `TimeGridView.kt` | "+N more" — `onClick = {}` |

The `FilterRow` helper made the intent explicit in its own source: its trailing icon
was `Icons.Default.Close` with the comment *"placeholder — will be ChevronRight"*.

## Idea

Either build calendar filtering, or remove the affordances that claim it exists.

## Decision

**Removed**, and the two "+N more" labels wired to what they *can* honestly do.

- "More" and "Filter" in the top bar: deleted.
- The 5 filter rows and the now-dead `FilterRow` composable: deleted.
- `MonthGridView` "+N more": now opens the day (`onClick = onClick`), which is where
  the full task list already lives.
- `TimeGridView` "+N more": left as plain text. It sits in the all-day cell of a day
  the user is already viewing, so "open the day" is a no-op there. `MoreTasksLabel`
  takes a nullable `onClick` and skips `.clickable` when absent, rather than rendering
  a control that does nothing.

## Rationale

This is the same judgement as
[`2026-09-29-destroyed-but-not-deleted-callbacks.md`](2026-09-29-destroyed-but-not-deleted-callbacks.md),
applied to a slightly different symptom. There, a control lied by doing nothing. Here
a control lies by *existing*: there is no handler to miss, because the feature it
advertises was never built.

A user who taps "Filter" and sees nothing concludes the app is broken. A user who
cannot see "Filter" concludes the app has no filtering — accurate, and something they
can decide about. The first costs trust; the second costs nothing.

Building filtering was the other option and was rejected for scope, not merit: it is a
feature (new state, new query parameters, new sheet, new persistence for the chosen
filters), not a wiring change. It does not belong inside a PR whose stated job is
"connect the callbacks".

The two "+N more" labels were the exception worth keeping — the underlying capability
(genuinely hidden tasks) is real, and there was an honest action available for one of
the two call sites.

## Consequences

- The calendar top bar is visually shorter. No functional loss, because nothing it lost
  ever worked.
- `MoreTasksLabel`'s signature changed to `onClick: (() -> Unit)?`. It is an internal
  calendar component with two call sites, both updated.
- `FilterRow`, `ImageVector` and several icon imports became dead and were removed.
- If calendar filtering is built later, the affordances go back — as real controls
  wired to real state, which is what they were pretending to be.
- The `AgendaStartRoute`/`CalendarIntent` audit should be repeated for other features:
  an intent list is a reliable index of what a screen can do, and anything a screen
  renders that has no matching intent was never finished.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/calendar/presentation/components/calendar/CalendarTopBar.kt`
- `.../calendar/MiniCalendarPanel.kt`, `.../MonthGridView.kt`, `.../TimeGridView.kt`, `.../TaskChip.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/calendar/presentation/state/CalendarIntent.kt`
- Same family: `2026-09-29-destroyed-but-not-deleted-callbacks.md`,
  `2026-09-30-card-level-ai-actions-deferred.md`
