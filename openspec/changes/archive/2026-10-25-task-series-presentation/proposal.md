# task-series-presentation

## What

A series is several task rows that share one id and stand for one piece of work.
Three things follow from that, and none of them was decided when the series was
made:

1. **A row of a series says what it is.** The calendar's chip carries the mark
   of a series; the day's list carries the mark and the number of dates the
   work has.
2. **A deleted series can be put back.** Deleting the whole series archives
   every occurrence and offers the way back, with the number of dates the user
   is about to lose.
3. **The statistics count works.** A series is one work: it counts once, as
   completed on the day it was first completed, and — while nothing of it is
   completed — as overdue once, on the day of its earliest missed date.

## Why

A row of a series looks like a task. In the calendar it is a chip among the
chips of that day, in the day's list it is a row among the rows, and nothing
says that this row and the row three days down are one piece of work. So the
user reads them as two things, and the "apply to all of them" button reads as a
feature of the row rather than as the truth about the work.

Deleting a series cannot be taken back. Deleting one occurrence has an undo
next to it; deleting the whole series archives every occurrence at once, and
the offer to put them back does not exist. The series is several dates of one
work, and the work is not what a person throws away by accident.

The statistics count occurrences. A series scheduled on seven dates with three
of them completed says "three things finished" — but the user did one thing, on
three of the seven dates they had scheduled it for. The number the screen shows
is the number of rows, and the number of rows is not what the user did.

## The change in behaviour

- A row that belongs to a series **shows the mark of a series**: an icon, and —
  where there is room — the number of dates the work has. The day's list shows
  the count; the calendar's chip shows the icon alone.
- Deleting a series **archives every occurrence and offers the way back**. The
  message says how many dates went away. The undo restores exactly the
  occurrences that went away, and the screen navigates back.
- The statistics count **works**: the copies of a series count once. A work
  counts as completed on the day it was first completed; while nothing of it is
  completed, it counts as overdue once, on the day of its earliest missed date.
  A work the user has started is not overdue.

## What is not in this change

- The series is not a table, and it does not become one.
- No new screen and no new dialog. The undo is the same snackbar the delete of
  one occurrence already uses.
- The dates of a series are not moved by any of this.
- The rules that keep a series saying one thing about the work are a separate
  change — the one that made the rows agree in the first place.

## Links

- `docs/decisions/2026-10-22-a-series-shows-itself-where-it-appears.md`
- `docs/decisions/2026-10-22-task-series-materialized-copies.md`
- `docs/decisions/2026-10-22-the-series-says-one-thing-about-the-work.md`
