---
title: "A series shows itself where it appears, and the statistics count works"
date: 2026-10-22
status: accepted
tags: [tasks, series, statistics, ui]
---

# A series shows itself where it appears, and the statistics count works

## Context

A series is several task rows that share one id and stand for one piece of work
([2026-10-22-task-series-materialized-copies.md](2026-10-22-task-series-materialized-copies.md)).
The rows carry the work's fields — the title, the project, the tags — and their
own occurrence's fields — the date, the time, the completion state.

Three things followed from that, and none of them was decided when the series
was made.

**A row of a series looks like a task.** In the calendar, a date of a series is
a chip among the chips of that day. In the day's list, it is a row among the
rows. Nothing says that this row and the row three days down are one piece of
work — so the user reads them as two things, and the "apply to all of them"
button in the detail screen reads as a feature of the row rather than as the
truth about the work.

**Deleting a series cannot be taken back.** The detail screen deletes a single
occurrence with an undo next to it. Deleting the whole series archives every
occurrence at once, and the offer to put them back does not exist: the
occurrence that was on screen is gone, and the others are gone with it. The
series is several dates of one work, and the work is not what a person throws
away by accident.

**The statistics count occurrences.** The statistics count completed tasks per
day. A series scheduled on seven dates, with three of them completed, says
"three things finished" — but the user did one thing, on three of the seven
dates they had scheduled it for. The number the screen shows is the number of
rows, and the number of rows is not what the user did.

## Idea

- A row that belongs to a series **says so**, and says how many dates the work
  has.
- Deleting a series **offers the way back**, with the number of dates the user
  is about to lose.
- The statistics count **works**: a series is one work, counted once.

## Decision

**The mark is one thing, drawn in two places.**

`SeriesMarker` is the mark: an icon, and — where there is room for a word — the
count of the dates. The calendar's chip carries the icon alone, because a chip
has no room for a sentence. The day's list carries the icon and the count,
because a row has room and because that is where the user reads the day's work.

The count is the number of rows that share the id. The list knows it: the
agenda evaluates its sections against the whole task list, so the number of
dates of a series is counted once for the list rather than per row.

**The undo of a series carries the ids that went away.**

`archiveSeries` returns the ids it archived, and the undo restores exactly
those. The alternative — restoring "the members of that series" — would restore
a series that had meanwhile changed: a date added between the deletion and the
undo would come back with the work, and a date removed would not.

The undo is a snackbar, not a confirmation dialog with a list of dates to read.
A dialog asks the user to make a decision about a deletion they have already
made; the snackbar says what happened, says how much, and offers the way back.
The count is in the message: "Серия удалена · 3 даты".

**A deleted series navigates back**, the way an archived task does. The screen
was open on a task that no longer exists.

**The statistics count works, and a work is counted once.**

The copies of a series are one work. The work counts as completed on the day it
was **first** completed — the day the work started being done; the later
copies are the same work carried on. While nothing of the work is completed,
it counts as overdue once, on the day of its **earliest** missed date. And a
work that the user has started is not overdue: it is under way.

The grouping happens before the buckets are counted, so no bucket can see two
copies of the same work.

## Rationale

**Why the mark carries the count and not just the icon.** The icon says "this is
one date of a work". The count says how many dates the work has — which is the
number the user needs when they ask "how much of this is left?". The calendar
has no room for the number; the day's list does, and the day's list is where
the user plans.

**Why the undo restores ids and not a series.** A series is a set of rows, and
the set changes: the user adds a date, or removes one, between the deletion and
the undo. The ids are what actually went away, so they are what comes back.

**Why the count of the dates is in the message.** The user has just thrown away
three dates of something they cared about. "Deleted" alone does not let them
judge whether that was what they meant; "three dates" does.

**Why the first completion is the day of the work.** The work was finished on
the day it was first completed — the later dates are the same work scheduled
again. Counting the last one instead would move the work a week forward and
make the statistics say the user was busy on days they were not.

**Why a started work is not overdue.** "Overdue" says the user has not done
the work. A work the user has already started — one occurrence of it is
completed — is not something they have not done; it is something they are in
the middle of. Counting it as overdue would say the same thing twice: once as
finished and once as failed.

**Why the grouping is a pure function.** The rule is a rule about works, not
about rows, and the statistics screen is not the only place that counts things.
A pure function can be tested without a database, and the test says what the
rule is rather than what the screen shows.

## Consequences

- A row of a series is marked everywhere it appears, and the mark is one
  component: the calendar and the list cannot drift apart.
- The series deletion can be taken back, and the undo says how much was lost.
- The statistics of a series-heavy profile stop counting the same work several
  times. A profile that schedules a series on seven dates and completes three
  of them shows one completed work, on the day it was first completed.
- The rule is stated once, in `groupIntoWorks`, and its test says it.

## Links

- [2026-10-22-task-series-materialized-copies.md](2026-10-22-task-series-materialized-copies.md) —
  what a series is.
- [2026-10-22-the-series-says-one-thing-about-the-work.md](2026-10-22-the-series-says-one-thing-about-the-work.md) —
  the fields that describe the work, and who keeps them in step.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/SeriesMarker.kt` —
  the mark.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/statistics/StatisticsDomain.kt` —
  the rule that counts works.
