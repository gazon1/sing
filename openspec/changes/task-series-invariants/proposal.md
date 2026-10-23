# task-series-invariants

## What

A series is several task rows that share one id and stand for one piece of work.
The rows carry the work's fields — the title, the project, the tags, the priority
— and their own occurrence's fields — the date, the time, the completion state.
Until now, nothing kept the two kinds apart in the storage: a write of one row
could leave the series saying two different things about the work, and there was
no way to take one date out of a series, or to bring two series together.

This change adds four behaviours:

1. **The series says one thing about the work.** A single-row write realigns the
   other rows of the series to the row that was written. A batch write that
   claims to be one series but carries two series ids is refused rather than
   written.
2. **A row that arrives from another device realigns the local series to it.**
   The last write wins, and the series follows the writer — so two devices stop
   disagreeing about the title of one piece of work.
3. **One date of a series can leave it.** The occurrence keeps its date and its
   own state; it stops being a copy. A series of one stops being a series.
4. **Two series can be joined.** The series the user is looking at keeps its id
   and its fields; the other one takes them, and both keep their dates.

## Why

The editor writes a shared field to the whole series, but the editor is not the
only writer. An MCP tool renames one occurrence, a push from another device
brings a row with another title, a backup restore writes rows one by one. Each
of those writes one row, and the series is then two stories at once. The next
person to look at it cannot tell which title is the work's, and "apply to all of
them" stops being a sentence about the series.

The same gap shows up in the other direction. A series is a decision the user
makes once: schedule three dates for one thing, and the app never asks again.
But the decision can be wrong — the second date needs another title, or is not
this job at all — and there is no operation that says so. The cost is paid on
every series that was slightly wrong, and it is paid silently.

The sync gap is the same gap with two devices on it. Each device holds a series,
each writes one row of it, and without a rule the two keep disagreeing about the
title forever: each device's own row says its own title, and each device pushes
that row back.

## The change in behaviour

- A write of one row of a series **repairs** the series: the other rows take the
  shared fields of the row that was written, and the rows that already agree are
  not written at all.
- A write of a batch of rows **refuses** a batch that carries two series ids,
  and refuses a batch whose members carry no series id at all where a series is
  meant. The refusal is loud, and nothing is written.
- A row that arrives from the server **repairs** the local series to that row's
  shared fields, and the rows that changed are pushed back.
- The user can ask one date of a series to **leave** it, from the series card on
  the task detail. The date keeps everything of its own; the series of one
  becomes a task that stands alone.
- The user can ask two series to **join**, from the agenda's selection panel.
  The series the user stands on keeps its id and its fields; the other takes
  them; both keep their dates.

## What is not in this change

- The series is not a table, and it does not become one. It stays the set of
  rows that share an id.
- No new screen and no new dialog. Both operations are answers to actions the
  user was already going to take.
- The occurrence fields are not touched by any of this. A date is what the
  occurrence is.
- The presentation of a series — a badge in the calendar, a grouping in the
  day's list — is a separate change.

## Links

- `docs/decisions/2026-10-22-the-series-says-one-thing-about-the-work.md`
- `docs/decisions/2026-10-22-a-series-can-be-split-and-joined.md`
- `docs/decisions/2026-10-22-task-series-materialized-copies.md`
