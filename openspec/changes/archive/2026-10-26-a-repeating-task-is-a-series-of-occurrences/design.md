# Design — a repeating task is a series of occurrences

**Related:** `docs/decisions/2026-10-25-a-recurring-series-carries-the-rule-on-every-row.md`
**Spec:** `specs/015-recurring-series-badges/spec.md` · **Plan:** `specs/015-recurring-series-badges/plan.md`

## The shape of the problem

A repeating task was one row that moved. Closing it stamped `completed_at` and then
moved `due_date`; the move never touched `completed_at`, so the row ended up closed and
dated in the future. One row cannot hold a future — it can only be somewhere else, and
wherever it is, the previous occurrence is gone.

The repo already solves the neighbouring problem. ADR `2026-10-22-task-series-materialized-copies`
decided that a series is **several task rows sharing one `TaskSeriesId`**, with the
shared fields duplicated on every row and the per-occurrence fields on their own row.
A series shows a future because a future is several rows.

That ADR closed with: *"a series never holds a rule"*. The clause was right about the
series it described — a set of **picked** dates, where a rule would generate
occurrences nobody asked for — and wrong about the one needed now.

## Two kinds of series

| Kind | The dates are | The rule on the rows | Planned by |
|---|---|---|---|
| Multi-date work | the user picked them | none | `TaskSeriesPlanner` (existing) |
| **Recurring series** | the rule's occurrences | the rule, on every row | `RecurringSeriesWindow` (the dates) + `RecurringSeriesPlanner` (the rows) |

The two never mix. A series either has picked dates and no rule, or the rule's own dates
and the rule on every row. The rule is what tells the kinds apart.

`TaskSharedField.Recurrence` is already a shared field, so the rows must agree on the
rule — and they do, because they are generated from one rule.
`TaskSeriesInvariants.alignedIfOneSeries` copies the first row's shared fields onto
every row, which for a recurring series copies the same rule onto every row: the
agreement is enforced, not hoped for.

**The window and the row planner are two things.** `RecurringSeriesWindow` answers
*which dates*; `RecurringSeriesPlanner` answers *which rows* — the members to create,
the ones to remove when the rule changes, the row that becomes the current occurrence,
and the one that leaves the series at the ceiling.

## The upstream capability `task-series`

`origin/main` carries a capability that owns the model of the series:
`openspec/specs/task-series/spec.md` (REQ-TS-001…REQ-TS-008). Three of its
requirements contradict this design literally, and the change therefore carries a delta
for it as well as for `tasks`.

| Upstream | Says | Here | How the delta says it |
|---|---|---|---|
| REQ-TS-001 | a single date is an ordinary task and must stay one | a single date **with a rule** becomes a series | The single date stays one; the exception is the rule, and it is stated in both. |
| REQ-TS-005 | a series of more than thirty dates is refused | a recurring series rotates at the ceiling | The refusal stays for picked dates; the recurring series rotates. |
| REQ-TS-007 | a rule and a set of dates must not be in force on the same task | the rule loses against picked dates (FR-048) | The prohibition is between a rule and **picked** dates; a recurring series is not in conflict. |
| `tasks` | «One date of a series can leave it» | — | The occurrence that leaves a recurring series also loses the rule. |
| `tasks` | «Two series can be joined» | — | The join refuses a mixed pair: a recurring series and a series of picked dates, and two recurring series whose rules differ. |
| `tasks` | «A recurring close is refused while a prerequisite is open» | the closed occurrence stays on its date | The refusal writes nothing; the advance is by materialising. |

**The upstream scenario names are kept verbatim.** The validator refuses a `MODIFIED`
block that drops a scenario the current spec still has, so «Confirming the refusal
closes the occurrence and **moves the series**» keeps its name while its body says what
"moves" now means: the closed occurrence keeps its own date, and the next occurrence is
materialised as a new row. Renaming the scenario would have been the tidier edit and the
one the validator refuses; saying it in the body is the edit that survives.

The `task-series` delta adds two requirements of its own — REQ-TS-009 (a series has one
kind, and the kind is decided by where its dates come from) and REQ-TS-010 (a recurring
series carries the rule on every member) — because the model of the recurring series is
not derivable from the three modified requirements alone.

## The two doors that set a rule

A rule reaches a row through the create screen or through the task screen, and **both**
have to materialise the series:

| Trigger | Path |
|---|---|
| The create screen | `TaskCreateViewModel.SetRecurrence` → `CreateTaskFromDraftUseCase` → `CreateTaskFromDraft` → `TaskSeriesPlanner.plan` |
| The task screen | `TaskEntitySlot.SetRecurrence` → `mutate` → `UpdateTask` → `repo.update` |

The create path is where the third defect lives: `TaskSeriesPlanner.plan` sets
`recurrence = null` on every row it writes (`TaskSeriesPlanner.kt:102`), including the
single row of a one-date draft. The draft carries the rule and the planner drops it, so
a task created with "repeat every week" came out with no rule at all.

**A rule together with picked dates: the rule loses.** The create screen already says
so when the user adds a second date to a repeating draft
(`RECURRENCE_DROPPED_MESSAGE`, `TaskCreateViewModel.kt:231-234`), and the design keeps
that answer rather than inventing a second one.

## The window

```
anchor   = the earliest open occurrence's date
           (or, when the series has no open occurrence, the latest occurrence's date)
horizon  = anchor + 14 days
within   = the rule's dates in (anchor, horizon], nearest first, capped at 10
beyond   = the rule's first date after the horizon, if the cap leaves room
window   = within + beyond
```

Pure: the caller supplies the rule and the anchor, so the window is a value that can be
asserted on. The date arithmetic reuses `RecurrenceCalculator.nextOccurrence`.

**The anchor is the series' position, not the calendar day.** That is the load-bearing
choice. A horizon measured from *today* makes the row set a function of the date, so
something has to notice the day change and write — a daily background job (which may
not have run before the user opens the app, so the first screen of the day is stale) or
a write on the read path (a read that mutates the database). Anchoring at the
occurrence removes the day from the rule and the scheduler with it.

**`beyond` is why a yearly rule still works.** Without it, a rule whose next date is
beyond the horizon would have no next occurrence at all.

**The base decides which date the anchor is.** `FROM_DUE` measures from the due date;
`FROM_COMPLETION` from the completion date — and while the occurrence is open, from its
due date, which is the best estimate available; `CATCH_UP` from the due date, because
catch-up fills the gaps between *scheduled* occurrences.

**The window honours the rule's end date itself.** `RecurrenceCalculator.nextOccurrence`
does not read `termination`, so the window checks the boundary through
`RecurrenceSpec.isExhaustedBy` — the single place the boundary is read.

## Closing

Every base now does the same two things: it closes the occurrence and it materialises
the next occurrence as a new row. The bases differ only in the anchor date they apply
the rule from.

The `FROM_COMPLETION` special case — which used to *reopen* the row and move it — is
gone. It existed because the one-row model had no other way to have a next occurrence.

The date no longer moves, so the pair of writes that produced the defect
(`completeOccurrence` then `moveDueDate`) is gone with it.

**A rule that has run out closes the occurrence and stops the series.** It used to be
soft-deleted, and the row went to the trash. Under the new model the series is over
precisely when no further occurrence is materialised, so nothing is archived: a closed
occurrence is already out of the active list.

## Leaving the series

The app already has the operation «take one occurrence out of its series»
(`DetachTaskFromSeries.kt:47-53`): the occurrence keeps its date, its time and its own
state, and loses the series id.

Under materialisation that operation had no answer for a recurring series. The
occurrence would have kept the rule — a row that carries the repeat badge and never
repeats, which is the one promise the badge must never make. So the occurrence that
leaves a recurring series loses the rule with the series id (FR-049), and the rest of
the series keeps it and continues from the members that remain.

The detach has a second write: when the occurrence that leaves was the second of two,
the row that stays behind also stands alone
(`DetachTaskFromSeries.kt:52-54`). That row loses the rule as well — a rule on a row
that stands alone is the shape the **repair** adopts into a new series, so a rule that
stayed would make the detach undo itself at the next launch.

The rule leaves the row on the wire as well: the detach writes the row's own fields,
and the row has no rule, so the document carries `seriesId: null` together with
`recurrence: null`. The sync capability reads a null value as the claim it is, so the
device on the other side clears both — and the row does not repeat there either.

## The join of two series

The app can join two series into one (`MergeTaskSeries.kt:40-56`): the series the user
is looking at keeps its id and its shared fields, and the other series' rows take them.
Under the two kinds that is the one operation that could make a series be two things at
once.

Joining a recurring series with a series of picked dates would leave the joined series
holding both a rule and hand-picked dates. Joining two recurring series whose rules
differ would leave its members disagreeing about the work — the one thing the rows of a
series may not do. Both are **refused**, and the refusal names the reason (FR-050). A
join of two series of the same kind — two series of picked dates, or two recurring
series carrying the same rule — succeeds as before, and the dates of both series
survive it.

## The write shape

A materialisation that creates members **and** detaches one is two calls inside one
transaction:

```kotlin
unitOfWork.write {
    repo.createSeries(newRows)     // the members the plan created
    repo.update(detachedRow)       // the row that leaves the series at the ceiling
}
```

`createSeries` refuses a batch carrying two series ids, and `updateAll` refuses to
invent a member — so the shape has to be two calls. `UnitOfWork.write` nests, so they
commit together. **Either order is safe**: the realignment that `repo.update` triggers
returns at once for a row that carries no series id, and the detached row is exactly
that.

## The ceiling

The existing ceiling of thirty rows is a refusal, and for a multi-date series it stays
one: thirty dates is what the user asked for. For a recurring series a refusal would
stop the recurrence, so the ceiling becomes a **rotation**: at the ceiling the oldest
closed occurrence leaves the series — its `series_id` is cleared, and **its rule goes
with it** — and stays an ordinary closed task. The mechanism already exists
(`DetachTaskFromSeriesUseCase` does `repo.update(task.copy(seriesId = null))`).

The rule has to go with the series id, for the same reason it does when the user takes
an occurrence out. A closed row that keeps its rule and has no series id is exactly the
shape the **repair** adopts into a new series, so a rule that stayed would make the
ceiling a loop: the repair would grow a fresh series around the rotated row at the next
launch.

## The repair

Rows that carry a rule and no `seriesId` are legacy repeating tasks. The repair runs at
launch, next to the bootstrappers that already run there, and it:

1. mints a `TaskSeriesId` and puts it on the row;
2. materialises the window beside it;
3. reopens the row **only when the series would otherwise have no open occurrence** — a
   row closed with a past date is history and stays closed.

It is a Kotlin pass, not a schema change: `tasks.series_id` and `tasks.recurrence_rule`
are both already there, and the arithmetic is the domain's, not SQL's. It is idempotent.

## The row badges

The repeat badge is derived from `task.recurrence != null` where the row is built —
the same derivation `AgendaBadgePolicy` already uses for `AgendaBadge.Recurring`. The
icon already exists (`Icons.Default.Repeat`, "Повторяющаяся задача"); it has never been
reachable, because the row is built with the flag hardcoded to `false` and because the
meta row's two gates (`hasMeta`, `hasSomethingToShow`) both omit `isRecurring`.

The project badge's data has to travel five hops that do not exist today:

```
ProjectsRepository.observeAllIncludingClosed()  →  AgendaDeps  →  AgendaViewModel (combine)
        →  AgendaRowItem  →  AgendaTaskRow  →  TaskUi  →  TaskMetaRow
```

The lookup must include **closed** projects: `observeAll()` filters
`closed_at IS NULL`, so a task whose project was closed would otherwise lose its badge
silently. It is resolved once per list build, not once per row.

**Two marks on a recurring row, and they are not the same mark.** The row of a recurring
series carries the repeat badge (`Icons.Default.Repeat`, "Повторяющаяся задача") *and*
the series marker that already exists (`EventRepeat` plus "серия · N дат",
`TaskMetaRow.kt:65-78`). They answer different questions: the series marker says "this
is one of N dates of one piece of work", the repeat badge says "this comes back". The
user asked for the second one, and the first one cannot answer it — a member of a
picked-dates series carries the same marker and does not repeat at all. Neither mark is
suppressed for the other; the cost is two small icons in the meta row.

## What the change does not touch

- The storage schema — no new column, no new table, no exported-schema bump.
- The read paths. The agenda, the calendar, the search index, the backup, the sync and
  the AI tools already know how to read a task row, and this change does not teach any
  of them a new one.
- The calendar's rule. The export keeps mapping every row to its own event, and the
  task's own rule is still never exported as a calendar rule.
