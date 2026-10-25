# Contract: The Recurring Series Window

The pure function that decides which future occurrences a repeating task holds. It is
the one place the answer lives; every writer of a series asks it rather than deciding
for itself.

Declared in `commonMain` under `feature/tasks/domain/logic`, next to
`TaskSeriesPlanner`, because it is the same kind of thing: a pure plan of what a series
holds, with no clock, no repository and no I/O.

## Shape

```kotlin
object RecurringSeriesWindow {
    const val HORIZON_DAYS: Int = 14
    const val MAX_FUTURE: Int = 10

    fun plan(
        rule: RecurrenceSpec,
        anchor: LocalDate,
        horizonDays: Int = HORIZON_DAYS,
        maxFuture: Int = MAX_FUTURE,
    ): List<LocalDate>
}
```

The caller supplies the rule and the anchor. The function never reads a clock, which is
what makes the window assertable: the same rule at the same anchor gives the same dates,
today and next year.

**The function honours the rule's end condition.** `RecurrenceCalculator.nextOccurrence`
does not read `termination` — it dispatches only on the rule's variant — so the window
checks the boundary itself, through `RecurrenceSpec.isExhaustedBy(nextDue)`, the single
place the boundary is read. A date past the rule's end date is not produced, and the
window stops there.

## Guarantees

| Guarantee | Requirement | How it is proved |
|---|---|---|
| The dates are the rule's own | FR-002 | every date comes from `RecurrenceCalculator.nextOccurrence`, the existing source of truth for a rule's dates. |
| The rule's end condition is honoured | FR-011 | the window checks `isExhaustedBy` for each date it considers, so a rule that has run out produces nothing — including the date past the end, which is why `beyond` can be empty. |
| The window is stable | FR-006 | the function is pure and the anchor is the series' position, not the calendar day. Nothing has to notice a new day for the window to change. |
| The window never grows without bound | FR-008 | `within` is capped at `MAX_FUTURE`. |
| A rare rule still has a next occurrence | FR-044 | `beyond` adds the rule's first date past the horizon when the cap leaves room — a yearly rule is not left with nothing. |
| The window never contains a date at or before the anchor | FR-004 | the dates are taken strictly after the anchor. The anchor itself is **not** in the window: it is the occurrence that is already there. |
| The window has no duplicates | FR-006 | the rule's dates are strictly increasing, and the two halves do not overlap. |

## The anchor

The anchor is the date the rule is applied from, and it is decided by the caller:

| Base | The anchor for the occurrence |
|---|---|
| `FROM_DUE` | the occurrence's due date |
| `FROM_COMPLETION` | the occurrence's **completion** date — while the occurrence is still open, its due date, which is the best estimate available |
| `CATCH_UP` | the occurrence's **due date** — the schedule, not the close |

`CATCH_UP` is anchored on the due date because that is what makes it catch-up: the gaps
it fills are the occurrences that were *scheduled* and missed, and they are dated from
the schedule. The current implementation anchors it there
(`CompleteRecurringTaskUseCase.kt:97`), and this design keeps that behaviour. The rule's
own documentation calls `CATCH_UP` "like FROM_COMPLETION"
(`RecurrenceSpec.kt:27-28`), which is looser than the implementation — see the change
log.

The occurrence the anchor is taken from is the series' **current occurrence**: the
earliest open one, or — when the series has no open occurrence — the latest one
(FR-043).

This is FR-043, and it is a rule about the series, not about the calendar. A series the
user has not touched for a month still shows the same window it showed a month ago,
because the work has not advanced either.

### What happens when the anchor moves

Under `FROM_DUE` the anchor is the due date, and closing an occurrence never moves it:
the next occurrence is due on the date the rule gives, whether the user closed it on
time or late. **The plan never shifts.**

Under `FROM_COMPLETION` the anchor is the completion date, so closing an occurrence
*does* move it — by however late the close was. The plan is then re-derived from the
new anchor, and the rows that still belong to the new plan keep their identity and
move their date; the rows that no longer belong are removed and the missing ones are
created.

That is the base's own meaning — "every week from when I did it" — and the cost is
stated in the ADR's Consequences rather than discovered later.

## Who asks this function

| Caller | When | What it does with the answer |
|---|---|---|
| `MaterializeRecurringSeriesUseCase` | a rule is set or changed, an occurrence is closed | creates the missing rows, removes the ones the rule no longer produces |
| `RepairRecurringSeriesUseCase` | application launch, for every legacy repeating task | brings the row into a series and fills the window |
| `CompleteRecurringTaskUseCase` | an occurrence is closed | re-plans the window from the new anchor |

## What the function does not decide

- **Whether a close is allowed.** The refusal is the guarded statement in the write
  (`markCompleteIfNotBlockedForUser`), not a check that runs before it — a check-then-write
  has a window between the two, and the window is where another device reopens the
  prerequisite.
- **Whether the row is created.** The window says which dates; the use case says which
  rows, and the repository writes them in one transaction.
- **What the rows carry.** The window is about dates; the shared fields come from
  `TaskSeriesInvariants.alignedIfOneSeries`, which copies them onto every row.

## The ceiling interaction

The window's `MAX_FUTURE` bounds the future rows. The series' total is bounded
separately, by the existing ceiling of thirty rows. When a series reaches it, the
oldest **closed** occurrence leaves the series — its `seriesId` is cleared — and stays
an ordinary closed task (FR-009, FR-045). The window function does not make that
decision: it plans dates, and the use case decides which row leaves.

## The rule's bases

The window is base-agnostic: it takes a rule and an anchor and produces dates. Which
anchor date the rule is applied from is the caller's business, and it is where the
three bases differ:

| Base | The anchor the caller passes |
|---|---|
| `FROM_DUE` | the occurrence's due date |
| `FROM_COMPLETION` | the occurrence's completion date |
| `CATCH_UP` | the occurrence's due date, with the missed occurrences materialised as closed rows first |

The bases differ only in the date they produce. They no longer differ in *whether the
occurrence is closed* — that difference was an artefact of the one-row model and is gone
(research D4).

## Notes for the implementer

Points the design decides elsewhere, collected so that they are not re-derived:

- **The catch-up copies are not the window's job.** The window is clock-free; the
  occurrences the user missed are counted by the close path, which has a clock:
  `RecurrenceCalculator.missedCount(spec, anchor, today)`. They are materialised as
  closed rows dated in the past.
- **The anchor at a close is the occurrence being closed** — not the series' earliest
  open occurrence read afterwards, which would be the newly materialised row and would
  plan a window one period too far out (FR-043).
- **At the ceiling, the row that leaves is the oldest closed one.** If every row of the
  series is open, there is no closed row to rotate, and the ceiling is reached only by
  a series whose occurrences were all left undone — the materialisation then has no
  closed row to take and the cap stands as it is.
- **When the cap is reached, `beyond` is dropped.** The cap is a cap: the tenth future
  occurrence is the last one, and the date past the horizon is not added on top of it.
- **A legacy row closed in the past still anchors the window.** FR-043's second clause
  covers it: with no open occurrence, the position is the series' latest occurrence —
  the closed row — and the window is materialised beside it.
- **The launch repair does not block the first frame.** It runs in the launch hook
  beside the other bootstrappers, on a background scope, exactly as they do.
- **An occurrence that leaves the series loses the rule with it.** The window is not
  involved: the detach path clears the series id *and* the rule on the row that leaves a
  recurring series (FR-049), and the rest of the series keeps the rule. A row that keeps
  a rule it will never honour would carry the repeat badge forever.

## The write shape

The window says which dates; the write says which rows. The two are not the same call,
and the repository's API forces the shape:

- `createSeries(rows)` upserts, so it can both create and update — but it **refuses** a
  batch that carries two series ids (`TaskRepositoryImpl.kt:253`). A detached row
  (`seriesId = null`) and a member (`seriesId = <id>`) in one batch is exactly such a
  batch.
- `updateAll(rows)` updates only; it refuses to invent a member
  (`TaskRepositoryImpl.kt:292-293`).

So a materialisation that creates members *and* detaches one is **two calls inside one
transaction**:

```kotlin
unitOfWork.write {
    repo.createSeries(newRows)     // the members the plan created
    repo.update(detachedRow)       // the row that leaves the series at the ceiling
}
```

`UnitOfWork.write` nests: a nested call joins the outer transaction rather than opening
its own (`core/database/UnitOfWork.kt:40-42`), so the two writes commit together.

**Either order is safe.** `repo.update` does trigger `realignSeries`
(`TaskRepositoryImpl.kt:230`), but the row it is given is the *detached* one, whose
`seriesId` is null — and `realignSeries` returns at once when the row it is handed
carries no series id (`TaskRepositoryImpl.kt:349`). It never re-reads the members
here. The constraint that decides the shape is the batch refusal above, not an
ordering.
