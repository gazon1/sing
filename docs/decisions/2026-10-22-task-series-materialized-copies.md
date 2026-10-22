---
title: "A series is several task rows that share one id"
date: 2026-10-22
status: accepted
tags: [tasks, series, database, sync, ui, calendar]
---

# A series is several task rows that share one id

## Context

A task in this app has exactly one date, and everything around it assumes that:
the agenda groups by `dueDate`, the calendar draws one cell per day, the search
index carries one date per row, the backup export writes one date per task, the
Google Calendar push maps one task to one event, and the MCP and AI tools read
and write a single `dueDate`.

Some work is not one occurrence. "Pay the rent on the 1st, the 5th and the 10th"
is one obligation with three dates. "Water the plants on Monday, Wednesday and
Friday" is one chore with three dates. The user thinks in one job with several
dates; the data model can only say one job, one date.

The obvious modelling is a *series*: an entity with its own fields and a list of
occurrences, each occurrence carrying its own completion state, checklist and
reminders. That is what a scheduling app usually does, and it is the wrong shape
for this codebase.

The other obvious modelling is one task with a list of dates. That is simpler,
but it breaks the shape of the whole app: a task would have to be *read* to know
whether it is done on a given day, and every read path — the agenda, the
calendar, search, backup, sync, the AI tools — would have to know about
occurrences. The task would also have to keep a per-day checklist, and the
checklist repository, which stores `task_id`, would have to grow a second key.

## Idea

Keep the task as it is — one row, one date — and add one nullable column,
`series_id`. A task that belongs to a series carries the id of that series; a
task that stands alone carries `null`.

The series is not a table. It is the *set of rows that share an id*. The shared
fields — the title, the description, the project, the tags, the priority, the
accent, the emoji, the estimate, the recurrence — are stored on every row, and
editing one of them writes every row. The per-occurrence fields — the date, the
time of day, the completion state, the pin, the checklist, the attachments, the
reminders — stay on their own row.

The rows are materialized copies, not references to a parent. That is the whole
decision, and the rest of this record is about what it costs.

## Decision

**A series is several task rows that share a `TaskSeriesId`.**

- `Task.seriesId: TaskSeriesId?` — `null` for a task that stands alone. The
  column on the entity is `series_id`, nullable, added by the hand-written
  migration 45 → 46. Every task that existed before the upgrade reads back as
  `seriesId == null`, so nothing had to be backfilled. The migration is written
  by hand because the derived auto-migration rewrites the whole `tasks` table,
  and dropping that table fires the foreign key that points the notes at a task.
- **One date is not a series.** A draft with one date produces one ordinary task
  with no series id at all. The distinction is made once, in
  `TaskSeriesPlanner`, and every caller goes through it.
- **Several dates share one id.** The planner mints the id; the rows carry it.
- **The scope rule lives in one place.** `TaskSharedField` lists the fields that
  describe the work; `sharedFieldOf` maps an intent to a field or to nothing.
  A field in the list is written to the whole series; a field outside it is
  written to the occurrence. The time of day and the pin are the two
  occurrence-scoped fields where "the same on every date" is a choice the user
  makes — so they get a separate, explicit action, not a rule.
- **Writes to a series go through one transaction.** `createSeries` and
  `updateAll` write every row in one transaction: a series that landed halfway
  would be a set of rows that share an id and describe different work, and no
  reader could tell.
- **The ceiling is thirty dates.** A refusal, not a hint: five hundred tasks
  from one action is a way of loading the database, not a feature. The check is
  in the domain, so it holds for every entry point and not only for the picker
  that happens to show the message.
- **A repeat rule and a set of dates are different intentions.** A row that had
  both would generate occurrences nobody asked for, so picking several days
  drops the rule and says so.

## Rationale

**Why materialized copies and not an occurrences table.** Every existing read
path in this codebase reads `Task.title` and `Task.dueDate` directly. A
separate occurrences table means a JOIN in the agenda, in the calendar, in the
search index, in the backup export, in the Google Calendar push, in the MCP
tools and in the AI tools — and every one of those would have to learn what an
occurrence is. The materialized form keeps all of them working unchanged: a
series is visible in the agenda as three tasks, in the calendar as three events,
in the backup as three rows.

**Why the shared fields are duplicated rather than joined.** The same reason.
`Task.title` is read in a hundred places. If the title lived on a series row
and the tasks joined to it, every one of those reads would need the join, and
every write path would need to write two tables.

**What the duplication costs.** Two things, and both are bounded:

- *Write amplification.* Editing a shared field of a series of thirty writes
  thirty rows. That is the price of not joining, and it is paid only on an
  explicit user action.
- *Divergence.* If a row of a series is written outside the series path — by a
  sync merge, or by a query that updates one row — it can end up carrying a
  different title than its siblings. The series is then inconsistent, and there
  is no rule that says which row is right.

The second cost is the one that decides the design. It is bounded because the
next series edit rewrites every row, so the divergence heals itself; and it is
made rare because the only writers of a series are `createSeries` and
`updateAll`, both of which write the whole set in one transaction. A
contradicting design — a series table plus occurrence rows — would have the
same divergence problem, but with no way to heal it, because there would be two
sources of truth rather than N copies of one.

**Why the shared fields are a list and not a per-field scope.** A second list —
"fields shared per series, fields shared per occurrence" — would have to be kept
in step with the first, and the moment it drifts, a field gets silently applied
to the wrong scope. One list, and the scope of everything outside it, cannot
drift.

**Why the draft carries the extra dates instead of a set.** The primary date
stays a `DueDateOption` so that "Today" and "Tomorrow" keep meaning the day of
the *save*, not the day the draft was first written: a draft restored the next
morning is still "today", not "yesterday". The extra dates are concrete,
because there is no relative form for a day the user picked in a grid.

**Why the calendar enters the selection mode by a long press on a day that
already holds tasks.** The long press on an *empty* day keeps its old meaning —
create one task on that day. Changing that would have taken away a gesture
users have; the new gesture therefore lives where the old one does not.

## Consequences

- The agenda, the calendar, the search, the backup, the sync and the AI tools
  see a series as several tasks. No change was needed in any of them.
- A series occupies several rows. A user with a hundred series of three days
  has three hundred rows — which is what they would have had anyway, since
  those are three hundred occurrences.
- Editing a shared field of a large series is a large write. The transaction is
  the right one: a partial write would be worse.
- The sync allowlist gained `seriesId`. The server stores it and does not
  enforce it; the client wrote it, so the server had to accept it.
- The backup format gained `seriesId`. A backup without it would restore a
  series as unrelated tasks: the dates would survive, the connection between
  them would not.
- The `Task` row carries a nullable column that is `null` for almost every
  task. The index on it is nearly empty, and the column costs a few bytes per
  row.
- A task created with a repeat rule and then edited into a series is not a
  thing: the planner drops the rule, so a series never holds a rule.

## Links

- `feature/tasks/domain/logic/TaskSeriesPlanner.kt` — the plan of a series.
- `feature/tasks/domain/model/TaskSeriesPolicy.kt` — the shared fields.
- `feature/tasks/presentation/viewmodel/slot/TaskSharedFieldScope.kt` — the
  one place that maps an intent to a field.
- `feature/tasks/domain/usecase/UpdateTaskSeries.kt` — the read-modify-write
  of a whole series.
- `core/ui/components/sheet/MultiDatePickerSheet.kt` — the picker.
