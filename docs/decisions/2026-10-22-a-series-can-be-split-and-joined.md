---
title: "A series can be split and joined"
date: 2026-10-22
status: accepted
tags: [tasks, series, agenda]
---

# A series can be split and joined

## Context

A series is several task rows that share one id and stand for one piece of work
([2026-10-22-task-series-materialized-copies.md](2026-10-22-task-series-materialized-copies.md)).
The user creates one with a few taps, and the app never asks again.

But a series is also the answer to a question that can be wrong. The user
scheduled three dates for one thing, and then discovers that the second date
needs a different title — or that the second date is not this job at all. There
is no way to take one date out of the series: the rows are joined by the id, and
the only operations on a series are "write it" and "archive it".

The same thing happens the other way round. Two series that are one piece of
work — "pay the rent" on the 1st and on the 5th, created as two series because
the user did not think of them as one — can never be brought together.

Without those two operations, the series is a decision the user makes once and
lives with. The cost of that is paid on every series that was slightly wrong,
and it is paid silently: the app shows a series of three dates, and the user
cannot make one of them something else.

## Idea

Two operations, each of which the user asks for explicitly:

- **Leave the series** — one date of the series becomes a task of its own. The
  date keeps everything it had; it loses the claim to be part of the work.
- **Join the series** — two series become one. The dates of both are kept; the
  work is described once.

## Decision

**Leaving is a row that loses its series id, and nothing else.**

The occurrence keeps its date, its time, its completion state, its checklist and
its attachments. It loses `series_id`, and with it the claim to be one of the
copies. If it was the last member, the series is over: a series of one is not a
series, and the row that is left stands alone.

**Joining keeps the id of the series the user is looking at.**

The user stands on one occurrence and asks for the other series to join it. That
series keeps its id — the one the screen, the URL and the other devices already
know — and its shared fields. The other series' rows take the shared fields of
the one the user is looking at, and take its id. Their dates are their own.

**Both are use cases, and both are domain rules.**

`DetachTaskFromSeriesUseCase` and `MergeTaskSeriesUseCase` are the two places
that know what the operations mean. The screens call them; they do not decide.

## Rationale

**Why the joined-in series changes its fields, and the other one does not.** The
user is looking at one of the two series and says "this is the same work". The
series they are looking at is the one whose title they have read, and the one
whose id their other devices know. So that series is the one that stays, and
the other one is the one that takes its meaning. The alternative — merging into
whichever series happens to be first in the list — would make the result depend
on the sort order.

**Why the dates are never touched.** A date is what the occurrence *is*. Joining
two series does not move a date, and leaving a series does not move one either.
The only thing that changes is which work the date belongs to.

**Why a series of one stops being a series.** A series of one is a task with a
claim attached. The claim says "there are other dates of this work", and there
are none. Keeping the id would leave a series that no screen can show as
anything but a task, and that a later join would treat as a series of one.

**Why the operations are not one operation.** "Take this date out" and "put that
work in here" are different questions, and the user asks them at different
moments. One rule for both would mean one of them is a special case of the
other, and the special case is the interesting one.

## Consequences

- The task detail screen offers "Оставить отдельно" on the series card, and the
  agenda offers to join two series when exactly two of them are selected. No
  new screen, no new dialog: the operations are answers to actions the user was
  already going to take.
- The domain has two use cases with their own tests; the screens have one
  button each, with a test tag so the flows can be driven.
- Leaving a series is undoable in the sense that the row is not deleted — it
  simply stops being a copy. Joining is not undoable by a single action; the
  user can leave the rows again.
- A series of one is never written: the detach that empties a series writes the
  last member without a series id.

## Links

- [2026-10-22-task-series-materialized-copies.md](2026-10-22-task-series-materialized-copies.md) —
  what a series is.
- [2026-10-22-the-series-says-one-thing-about-the-work.md](2026-10-22-the-series-says-one-thing-about-the-work.md) —
  the fields that describe the work, and who keeps them in step.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/usecase/DetachTaskFromSeries.kt` —
  the operation of leaving.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/usecase/MergeTaskSeries.kt` —
  the operation of joining.
