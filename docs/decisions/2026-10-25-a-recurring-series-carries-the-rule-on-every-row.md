---
title: "A recurring series carries the rule on every row"
date: 2026-10-25
status: accepted
tags: [tasks, series, recurrence, database, sync, ui]
supersedes: 2026-10-22-task-series-materialized-copies
---

# A recurring series carries the rule on every row

## Context

ADR `2026-10-22-task-series-materialized-copies` decided how one piece of work with
several dates is stored: **a series is several task rows that share one `TaskSeriesId`**,
the shared fields are duplicated on every row, and the per-occurrence fields stay on
their own row. That decision stands, and this record carries it forward.

The repository also carries a capability that owns the model of the series in words —
`openspec/specs/task-series/spec.md` — and it describes the **picked-dates** kind. This
record separates the two kinds, and the change that follows carries a delta for that
capability: the refusal of a series of more than thirty dates stays for picked dates and
becomes a rotation for a recurring one.

That ADR also closed with a clause that this feature contradicts:

> A task created with a repeat rule and then edited into a series is not a thing: the
> planner drops the rule, so **a series never holds a rule**.

The clause was right about the model it described. In that model a series was a set of
*picked* dates — "pay the rent on the 1st, the 5th and the 10th" — and a rule on such a
row would generate occurrences nobody asked for. So `TaskSeriesPlanner` drops the rule
(`TaskSeriesPlanner.kt:102`) and `TaskSharedField` lists the recurrence among the shared
fields so the rows agree on its absence.

The clause is wrong about the model that is needed now, because the recurring task was
never represented as a series at all. It was represented as **one row that moves**:

- closing the occurrence stamped `completed_at` on the row;
- the same operation then moved the row's `due_date` forward;
- and the move never touched `completed_at` (`Daos.kt:267-271` writes `due_date` and
  `updated_at` and nothing else).

The result was a row that was **closed and dated in the future**. It left the "Today"
section and came back in "Upcoming" struck through, and there was no next occurrence
anywhere — not in the agenda, not in the calendar, not in the search index. A user who
completed "buy bread — every week" saw nothing new, and the row itself said nothing
about repeating: the agenda built every row with `isRecurring = false` hardcoded
(`AgendaContent.kt:408`), so the repeat icon that already existed in the row
(`TaskMetaRow.kt:65-73`) had never been drawn by anything.

The user asked for two things: **the future of a repeating task has to be visible**, and
**a row has to say what it is**. Both need a representation change, and the
representation change needs a series that carries a rule.

## Idea

**A repeating task is a series whose rows carry the rule, and the rows' dates are the
rule's own occurrences.**

The row the user set the rule on becomes the current occurrence. The rule's future dates
become further rows of the same series. Every row carries the rule, so the rows agree on
it — which is what `TaskSharedField.Recurrence` already demands of a series.

The series is therefore of one of two kinds, and the kind is told by the rule:

| Kind | The dates are | The rule on the rows |
|---|---|---|
| **Multi-date work** | dates the user picked | none |
| **Recurring series** | the rule's occurrences | the rule, on every row |

The two never mix. A series either has picked dates and no rule, or the rule's own dates
and the rule on every row.

**The join of two series does not mix them either.** The app can join two series into
one, and the join takes the target's id and its shared fields onto the other series'
rows. A join of a recurring series with a series of picked dates would produce a series
whose members carry a rule and a hand-picked date, and a join of two recurring series
with different rules would produce members that disagree about the work — the one thing
a series may not do. Both are refused, and the refusal names the reason.

## Decision

### 1. A series has two kinds, and the rule tells them apart

`TaskSeriesPlanner` keeps producing the multi-date kind and keeps dropping the rule. A
new planner produces the recurring kind and keeps it.

`TaskSharedField.Recurrence` stays a shared field. It is already one, and a recurring
series satisfies it by construction: the rows are generated from one rule, so they agree.
`TaskSeriesInvariants.alignedIfOneSeries` copies the shared fields of the first row
onto every row, which for a recurring series copies the same rule onto every row — the
agreement is enforced rather than hoped for.

### 2. The window is a pure function of the rule and the series' position

The series holds a **window** of future occurrences:

```
anchor   = the earliest open occurrence's date
           (or, when the series has no open occurrence, the latest occurrence's date)
horizon  = anchor + 14 days
within   = the rule's dates in (anchor, horizon], nearest first, capped at 10
beyond   = the rule's first date after the horizon, if the cap leaves room
window   = within + beyond
```

The anchor is the **series' position, not the calendar day**. The window therefore does
not move on its own: it changes when an occurrence is consumed or the rule changes, and
nothing else. No scheduler is involved — no daily job, no write on the read path — and
the series converges without the day of the month entering the rule.

The **base decides which date the anchor is**. `FROM_DUE` measures from the
occurrence's due date. `FROM_COMPLETION` measures from the occurrence's *completion*
date — and while the occurrence is still open, from its due date, which is the best
estimate available. `FROM_COMPLETION` is the default base, so this is the common path
and not an edge case. `CATCH_UP` measures from the occurrence's **due date**: catch-up
fills the gaps between *scheduled* occurrences, so its dates come from the schedule,
and that is where the current implementation anchors it.

### 3. Closing an occurrence closes it and materialises the next one

Every recurrence base now does the same two things: it closes the occurrence, and it
materialises the next occurrence as a **new row**. The bases differ only in the anchor
date they apply the rule from.

The occurrence stays on its own date. The row's date never moves, which removes the
defect: there is no longer a row that is closed and dated in the future.

### 4. A rule that has run out closes the occurrence; nothing is archived

Today, a series whose next date falls past its end date is **soft-deleted** — the row
goes to the trash, where the user can find and restore it. That was the right call for
a model where the single row *was* the series: archiving it removed the finished series
from the active list.

Under materialisation the series is a set of rows, and the series is over precisely when
no further occurrence is materialised — which the rule's end date already decides. So
the last occurrence is **closed** and the series stops growing. **Nothing is
archived.** A closed occurrence is already out of the active list, and archiving a row
the user closed would move finished work into the trash, which is a different thing
from ending a schedule.

### 5. The ceiling rotates for a recurring series

The existing ceiling of thirty rows per series was a refusal, and for a multi-date
series it still is: thirty dates is what the user asked for. For a recurring series a
refusal would mean the recurrence stops — a daily task would stop recurring after
thirty completions — so the ceiling becomes a **rotation**: at the ceiling the oldest
closed occurrence leaves the series, its `series_id` is cleared **and its rule goes with
it**, and it stays an ordinary closed task. Nothing is destroyed.

The rule has to go with the series id, for the same reason it does when the user takes
an occurrence out: a closed row that keeps its rule and has no series id is exactly the
shape the launch repair adopts into a new series, so a rule that stayed would make the
ceiling a loop — the repair would grow a fresh series around the rotated row at the
next launch.

### 6. Existing repeating tasks are repaired at launch

Rows that already carry a rule and no `seriesId` become series at application launch, in
the same place the profile and background-work bootstrappers already run. The repair
mints the series id, materialises the window, and reopens the row **only when the
series would otherwise have no open occurrence** — the row the old roll-forward left
closed with a future date is exactly that case. A repeating task closed in the past is
history and stays closed.

The repair is a Kotlin pass, not a schema change: `tasks.series_id` and
`tasks.recurrence_rule` are both already there, and the arithmetic is the domain's, not
SQL's. It is idempotent, so running it twice changes nothing the second time.

**Its scope is the active profile.** The pass runs against the profile the app was
launched with, because the repository's reads are scoped to the active profile id. A
second profile's legacy repeating tasks are repaired at the **next launch with that
profile active** — not on a profile switch, because no such trigger is designed here.

## Rationale

**Why materialised copies and not an occurrences table.** Carried forward from the
superseded ADR, and unchanged: every existing read path reads `Task.title` and
`Task.dueDate` directly. An occurrences table would put a JOIN in the agenda, the
calendar, the search index, the backup export, the Google Calendar push, the MCP tools
and the AI tools, and each of them would have to learn what an occurrence is. The
materialised form keeps all of them working: a recurring series is visible in the agenda
as several tasks, in the calendar as several events, in the backup as several rows.

**Why the rule on every row and not on one head row.** `TaskSharedField.Recurrence` is
a shared field, so `realignSeries` collapses the rows onto the written row's value on
the next unrelated write (`TaskRepositoryImpl.kt:348-424`). A rule that lived on one row
would be erased by an edit to a sibling. It would also leave the repeat badge
underivable from a row's own data, which is what the row needs to say it repeats.

**Why the window is anchored at the occurrence.** A horizon measured from *today* makes
the set of rows a function of the calendar day, so something has to notice the day
change and write. That something would be a daily background job — which on a suspended
desktop or a phone in Doze may not have run before the user opens the app, so the first
screen of the day would show a stale window — or a write on the read path, which makes
a read mutate the database. Anchoring at the occurrence removes the day from the rule,
and the scheduler with it.

**Why the calendar is not special-cased.** The export maps every row to its own event
(`CalendarSyncWorker.kt:70-88`, `GoogleTaskApplier.kt:215-218`) and has never grouped
by series id. A recurring series therefore appears as one event per open occurrence —
the same rule a multi-date series already follows. Grouping a recurring series down to
one event would make the calendar disagree with the agenda, which shows every
occurrence.

**Why the rule is never exported as a calendar rule.** The calendar's recurrence comes
from a *reminder's* pattern (`CalendarEventMapper.kt:69-74`), and the Google path sets
`recurrenceRule = null` deliberately — "Never from the task" (`GoogleTaskApplier.kt:243-246`).
This decision does not change that.

## Consequences

- **The agenda, the calendar, the search, the backup, the sync and the AI tools see a
  recurring series as several tasks.** No change is needed in any of them — which is the
  property the materialised-copies decision bought, and which this feature spends.
- **A repeating task occupies several rows.** A weekly task holds three; a daily task
  holds ten. That is what the user asked for — the occurrences are real tasks, visible
  and completable on their own.
- **A row that repeats says so.** The repeat badge is derived from the task's own rule
  and is drawn by the row.
- **Closing a repeating task no longer moves it.** The task stays on its date, closed,
  and the next occurrence is a different row. Reopening works on the row the user
  closed.
- **A repeating task created from the create screen now repeats.** It did not: the
  create path goes through the series planner, which drops the rule from every row it
  writes, including the single row of a one-date draft. A task created with "repeat
  every week" came out with no rule at all, silently. The create path is now a
  trigger of the materialisation.
- **A rule set together with picked dates is dropped.** The create screen already says
  so when the user adds a second date to a repeating draft; the design keeps that
  answer rather than inventing a second one.
- **A series that runs out is closed, not archived.** It used to be soft-deleted, and
  the row went to the trash. Under the new model the last occurrence is closed and the
  series stops growing; nothing is archived, because a closed occurrence is already out
  of the active list.
- **Editing a shared field of a recurring series writes every row.** This is the write
  amplification the materialised-copies decision already accepted, and it is paid on an
  explicit user action.
- **Under "from completion", the visible future shifts when the user closes late.**
  The rule measures from the completion date, so closing three days late moves the
  window by three days. That is the base's meaning — "every week from when I did it" —
  and it is not a defect. The shift is applied as a date update on the rows that
  survive the re-plan, so a future row's own reminder or checklist is not lost to it.
  Under "from due date" the window never moves.
- **A repeating task's history grows with the series.** The series rotates at the
  ceiling: the oldest closed occurrence leaves the series and stays a task.
- **An occurrence that leaves a recurring series loses the rule.** It keeps its own date
  and its own state, and it stops being part of the recurrence — otherwise it would carry
  the repeat badge and never repeat. The rest of the series keeps the rule and continues
  from the members that remain. The row that stays behind when the series ends loses it
  too: a rule on a row that stands alone is a promise nothing keeps, and the shape the
  launch repair adopts into a new series.
- **Two series of different kinds cannot be joined.** The app can join two series; a
  recurring series and a series of picked dates are refused, and so are two recurring
  series whose rules differ. A join of two series of the same kind is unchanged.
- **The calendar gains events for a repeating task.** One event per open occurrence —
  three for a weekly task. This follows from the materialisation and from the
  calendar's existing one-row-one-event rule.
- **Whether a recurring series should ever be exported to the calendar as a single event
  with a rule is a calendar decision and is not taken here.** It is recorded so it is
  not lost.
- **The existing test that asserts the old behaviour is wrong and is rewritten.** A
  closed occurrence no longer rolls forward on the same row.

## Links

- `feature/tasks/domain/logic/RecurringSeriesWindow.kt` — the window function.
- `feature/tasks/domain/logic/RecurringSeriesPlanner.kt` — the plan of a recurring
  series.
- `feature/tasks/domain/usecase/MaterializeRecurringSeriesUseCase.kt` — the write that
  fills the window.
- `feature/tasks/domain/usecase/RepairRecurringSeriesUseCase.kt` — the launch repair.
- `feature/tasks/domain/usecase/CompleteRecurringTaskUseCase.kt` — close and materialise.
- `feature/tasks/domain/usecase/CreateTaskFromDraft.kt` — the create trigger.
- `feature/tasks/presentation/viewmodel/slot/TaskEntitySlot.kt` — the edit trigger.
- `feature/tasks/domain/logic/TaskSeriesPlanner.kt` — the plan of a multi-date series.
- `feature/tasks/domain/logic/TaskSeriesInvariants.kt` — the shared-field rules.
- `specs/015-recurring-series-badges/spec.md` — the feature specification.
- `specs/015-recurring-series-badges/research.md` — the decisions and what was rejected.
- ADR `2026-10-22-task-series-materialized-copies` — the superseded decision this one
  carries forward.
