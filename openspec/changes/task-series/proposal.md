# task-series

**Status:** proposed · **ADR:** `docs/decisions/2026-10-22-task-series-materialized-copies.md`

## What

Let one piece of work be scheduled on several dates. A *series* is several task rows
that share one id: the shared fields are edited once, for the whole series, and each
row keeps its own date, completion state, checklist, attachments and reminders.

The user can start a series from three places — the create editor, the calendar, and
the agenda — and every one of them ends in the same editor, so a task on several days
is created by the same code whichever screen asked for it.

## Why

Some work is not one occurrence. "Pay the rent on the 1st, the 5th and the 10th" is
one obligation with three dates. The model can only say one task, one date, so today
the only way to express it is three tasks that say nothing about each other: three
titles to keep in step, three projects to reassign, three tags to retag, and no way
to close all three at once.

The alternative — a series table plus an occurrences table — would put a JOIN in the
agenda, the calendar, the search index, the backup export, the Google Calendar push,
the MCP tools and the AI tools, and would make the task's own title unreadable
without the join. The decision to avoid that is recorded in the ADR.

## Decision required

None. The ADR records the decision: a series is several task rows that share a
`TaskSeriesId`, and the shared fields are stored on every row.

## Out of scope

- A series of tasks that recur by rule. A rule *generates* occurrences; a series is
  a fixed set of dates picked by hand. A row cannot have both.
- Editing the *dates* of an existing series as a set. The date of a series belongs
  to its occurrences; a series edit changes the work, not the schedule.
- Sharing a series between profiles.
