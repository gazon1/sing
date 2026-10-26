# Phase 1 Data Model: Repeat as a materialised series, and the row badges

Feature: `specs/015-recurring-series-badges/spec.md`
Date: 2026-10-25

This document describes the data the feature adds or changes. The storage layer itself
does not change: `tasks.series_id` and `tasks.recurrence_rule` already exist, so there is
no new table, no new column, and no exported-schema bump. What changes is **which rows
exist** and **what a row of the list carries**.

---

## 1. The series (recurring kind)

A recurring task is a series — the shape ADR `2026-10-22-task-series-materialized-copies`
already defines: several task rows that share one `TaskSeriesId`. This feature adds the
second kind of series to the model.

### The two kinds

| Kind | How the dates come about | `recurrence` on the rows | How it is created |
|---|---|---|---|
| **Multi-date work** | the user picked them | `null` | `TaskSeriesPlanner.plan` (existing) |
| **Recurring series** | the rule produces them | the rule, identical on every row | `RecurringSeriesPlanner` (new) |

The two kinds never mix. A series either has picked dates and no rule, or the rule's own
dates and the rule on every row. This is the clause the superseding ADR refines, and it
is what keeps `TaskSharedField.Recurrence` — a shared field — satisfied.

### Fields

Nothing new is stored. The series is the *set of rows*, and the fields are the task
fields the existing model already splits:

| Field | Scope in a recurring series | Source |
|---|---|---|
| `title`, `description`, `priority`, `projectId`, `tags`, `accentColor`, `emoji`, `estimateMinutes` | shared — identical on every row | `TaskSharedField` |
| `recurrence` | shared — the rule, identical on every row; a row that leaves the series loses it | `TaskSharedField.Recurrence` |
| `dueDate` | per occurrence | the rule's date for that occurrence |
| `completedAt` | per occurrence | the user's action |
| `seriesId` | shared | minted when the rule is set |
| `isPinned`, `dueTime`, reminders, checklist, attachments | per occurrence | the existing occurrence-scoped fields |

### Relationships

```
TaskSeries (implicit — a set of rows, not a table)
  └── Task × N          one row per occurrence, all sharing one seriesId
        ├── dueDate     the occurrence's date
        ├── completedAt open or closed
        └── recurrence  the rule, copied onto every row
```

---

## 2. The occurrence

An occurrence is an ordinary task row. It is not a new entity, and it is not a new
concept for any read path to learn: the agenda groups it by `dueDate`, the calendar
draws it, the search index carries it, the backup writes it, the Google Calendar push
maps it, and the MCP and AI tools read and write it. That is the property the existing
ADR bought by choosing materialised copies, and this feature spends it rather than
re-opening the question.

**Occurrence-scoped vs. series-scoped, in one place:**

| The user closes an occurrence | The series |
|---|---|
| the row stays where it is — on its own date, closed | gains the next occurrence as a **new row** |
| the row keeps its `seriesId` | every row keeps the same `seriesId` and the same rule |

### Validation rules

| Rule | Requirement | Where it is enforced |
|---|---|---|
| Every row of a recurring series carries the same rule | FR-001, FR-022 | `TaskSharedField.Recurrence` is shared; `TaskSeriesInvariants.alignedIfOneSeries` copies the first row's rule onto the rest, so a batch written through `createSeries` agrees by construction. |
| Every row of a recurring series carries the same `seriesId` | FR-001 | `createSeries` refuses a batch that carries two ids (`TaskRepositoryImpl.kt:253`). |
| A recurring series holds at most 10 future occurrences | FR-008 | the window function's `MAX_FUTURE`. |
| A series holds at most 30 rows | FR-009 | `TaskSeriesPlanner.MAX_DATES` — the existing ceiling. The window function does not enforce it: the series' *total* is the planner's bound, and the recurring kind rotates at it rather than refusing. |
| A closed occurrence is not reopened by the repair unless the series has no open occurrence | FR-036 | the repair's condition. |
| A refused completion changes nothing | FR-012, FR-013 | the guarded statement refuses inside the transaction, so nothing was written. |
| A rule that has run out closes the occurrence and stops the series | FR-047 | the window produces no date past the rule's end date, and the close path closes rather than archives. |

---

## 3. The window (the pure function)

The window is the set of future occurrences the series holds. It is a pure function of
the rule and the series' position — deliberately **not** of the current date (research
D2).

```
anchor   = the earliest open occurrence's date
           (or, when the series has no open occurrence, the latest occurrence's date)
horizon  = anchor + 14 days
within   = the rule's dates in (anchor, horizon], nearest first, capped at 10
beyond   = the rule's first date after the horizon, if the cap leaves room
window   = within + beyond
```

**The anchor is the date the rule is applied from, and the base decides which date that
is.** For `FROM_DUE` it is the occurrence's due date. For `FROM_COMPLETION` it is the
occurrence's **completion** date — and while the occurrence is still open, its due
date, which is the best estimate available. For `CATCH_UP` it is the occurrence's **due
date**: catch-up fills the gaps between *scheduled* occurrences, so its dates come from
the schedule. The window function is base-agnostic; the caller supplies the anchor.

The rule's end condition is read by the window function itself, through
`RecurrenceSpec.isExhaustedBy(nextDue)` — the single place the boundary is read.
`RecurrenceCalculator.nextOccurrence` does not read it, so the check has to happen
where the dates are produced.

### Shape

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

Pure: no clock, no repository, no I/O. The caller supplies the rule and the anchor, so
the window is a value that can be asserted on. The date arithmetic reuses
`RecurrenceCalculator.nextOccurrence` (`feature/tasks/domain/logic/RecurrenceCalculator.kt`),
which is already the source of truth for a rule's dates.

### Why the window is what it is

| Property | Requirement | How it falls out |
|---|---|---|
| The same rule at the same position always gives the same dates | FR-006 | the function is pure and has no hidden input. |
| The window never exceeds the cap | FR-008 | `within` is capped at `maxFuture`. |
| A rare rule still has a next occurrence | FR-044 | `beyond` adds the first date past the horizon, so a yearly rule is not left with nothing. |
| The window does not move on its own | FR-006 | the anchor is the series' position, not the calendar day — nothing has to notice a new day. |

### The materialisation plan

The write is a plan first and a write second, so the plan can be tested without a
database:

```kotlin
data class SeriesMaterialisationPlan(
    val toCreate: List<Task>,      // the new occurrences
    val toDetach: List<TaskId>,    // the closed rows leaving the series at the ceiling
    val toReopen: TaskId?,         // the row that becomes the current occurrence
)
```

| Situation | The plan |
|---|---|
| a rule is set on a task that stands alone | the task becomes the current occurrence, the window is created beside it |
| the rule changes | the future occurrences that the new rule does not produce are removed; the ones it does produce are created; the closed rows stay (FR-015) |
| the rule is removed | the series stops growing; the existing rows stay (FR-017) |
| an occurrence is closed | the occurrence stays in the series; the window is re-planned from the new anchor |
| the series is at the ceiling | the oldest closed row is detached (FR-009) |

**A re-plan moves dates, it does not rebuild rows.** The rows that still belong to the
new plan keep their identity and their per-occurrence state — a reminder, a checklist —
and only their date changes. A row is removed only when the new plan has no place for
it, which happens when the rule changes and the user has said something new, or when
the cap trims the tail.

Under `FROM_DUE` a re-plan never moves anything: the next occurrence is due on the
date the rule gives, whether the user closed it on time or late. Under
`FROM_COMPLETION` the plan shifts by however late the close was — which is what "every
week from when I did it" means — and the shift is a date update on the rows that
survive, so no per-occurrence state is lost to it.

---

## 4. The legacy repair

Existing repeating tasks are a single row with a rule and no `seriesId`. The repair
turns each of them into a series.

**Condition:** the row carries a rule and its `seriesId` is `null`.

**What it does:**

1. mints a `TaskSeriesId` and puts it on the row;
2. materialises the window beside it;
3. **reopens the row only if the series would otherwise have no open occurrence** — a
   row that the old roll-forward left closed with a future date is exactly that case,
   and it is the row the user is complaining about; a row closed with a past date is
   legitimate history and stays closed (FR-036).

**What it does not do:** no schema change, no table rewrite, no destructive migration
(research D5). The repair is idempotent — a row that already carries a `seriesId` is
not touched, so running it twice changes nothing the second time (FR-037).

**Where it runs:** at application launch, next to the bootstrappers that already run
there — `desktopApp/src/main/kotlin/com/singularity/todo/main.kt:121-126` and
`androidApp/src/main/kotlin/com/singularity/todo/SingularityApp.kt:174-180`.

**Where it finds its rows:** through a query of its own — "tasks of this profile that
carry a rule and belong to no series". No such query exists today: `Daos.kt` holds only
the two `series_id` queries, both `SELECT` by series id. Reading every active task and
filtering in memory would work, but it would pull every active task of the profile into
memory on every launch to find the handful that repeat. The query is a new statement on
an existing table — not a schema change — and `tasks.recurrence_rule` is indexed.

**Its scope is the active profile.** `observeAll()` is scoped to
`currentUser.scopedUserId`, which comes from the active profile id, so a second
profile's legacy repeating tasks are repaired at the next launch with that profile
active (FR-034).

**How it reopens a row:** `UPDATE tasks SET completed_at = NULL`
(`Daos.kt:222-223`), reached through `TaskRepositoryImpl.kt:703-711` — the write
already exists.

---

## 5. The row badge

The badge is not a new entity. It is a **presentation value** on the row, and the data
it needs is already in the task.

### What the row carries

| Field | Type | Source | Requirement |
|---|---|---|---|
| `isRecurring` | `Boolean` | derived: `task.recurrence != null` | FR-021, FR-022, FR-024 |
| `project` | `String?` | the project's **name**, resolved once per list build | FR-027 |
| `projectColor` | `Int?` | the project's ARGB colour, resolved once per list build | FR-028 |

`TaskUi` today carries `project: String?` and no colour (`feature/tasks/presentation/model/TaskUi.kt:47`),
so the colour is a new field and `isRecurring` becomes derived rather than hardcoded.

### How the project reaches the row

The colour has to travel five hops, and none of them exist today (research D6):

```
ProjectsRepository.observeAllIncludingClosed()  ← the name and the colour, once per list
        ↓
AgendaDeps.projectsRepository                   ← new dependency; the repository is already bound
        ↓
AgendaViewModel: combine(tasks, projects)       ← the existing flatMapLatest chain gains a combine
        ↓
AgendaRowItem.project / projectColor            ← the resolved pair rides on the row item
        ↓
AgendaTaskRow → TaskUi → TaskMetaRow           ← the badge is drawn
```

**The lookup must include closed projects.** `ProjectsRepository.observeAll()` reads
`WHERE user_id = :userId AND is_deleted = 0 AND closed_at IS NULL`
(`core/database/Daos.kt:843-847`), so a task whose project was closed would resolve to
no name and no colour, and the badge would silently vanish. The plan adds a lookup that
includes closed projects — a query on an existing table, not a schema change.

### The badge's own shape

```kotlin
data class ProjectBadge(
    val name: String,
    val color: Int,      // ARGB, as Project.color
)
```

| Rule | Requirement | Note |
|---|---|---|
| A row without a project has no badge | FR-029 | the badge is absent, not empty. |
| The badge replaces the plain project text | FR-030 | `TaskRowContent.kt:86` currently prints `task.project ?: "Без проекта"` — that placeholder leaves the row. |
| A long name is truncated | FR-031 | ellipsis, and the due date is not pushed out of the row. |
| Readable in both themes | FR-032 | the colour is the project's, the text is the theme's. |

---

## 6. State transitions of a recurring occurrence

```
                    ┌──────────────────────────────┐
                    │  materialised, open          │
                    │  (an occurrence in the window)│
                    └───────────┬──────────────────┘
                                │ the user closes it
                                │ (the guarded statement refuses while a
                                │  prerequisite is open — nothing changes)
                                ▼
                    ┌──────────────────────────────┐
                    │  closed, stays in the series │
                    │  on its own date              │
                    └───────────┬──────────────────┘
                                │ the user reopens it
                                ▼
                    ┌──────────────────────────────┐
                    │  open again                  │
                    └──────────────────────────────┘

  a closed occurrence at the series ceiling ──► detached: it leaves the series,
                                                 loses the rule with the series id,
                                                 and stays an ordinary closed task

  an occurrence the user takes out of the series ──► detached: it loses the rule
                                                        with the series id and stays
                                                        an ordinary task; the rest of
                                                        the series keeps the rule

  the last other member of a recurring series ──► detached: it stands alone and
                                                     loses the rule too
```

The transition that no longer exists is the one that caused the defect: **close and
move the date**. Under the new model the row's date never moves.

---

## What is deliberately not modelled

- **A series table.** The series is the set of rows that share an id. Nothing stores it
  separately, and no read path joins to it.
- **A window row.** The window is computed from the rule and the anchor; it is not
  stored, and no column holds "the series' horizon".
- **An occurrence entity.** An occurrence is a task row. The agenda, the calendar, the
  search index, the backup, the sync and the AI tools already know how to read a task
  row, and this feature does not teach any of them a new one.
- **A per-day checklist on a series.** The existing ADR rejected it, and this feature
  does not reopen it.
- **A calendar policy for recurring series.** The export follows the existing
  «one row — one event» rule (research D8). Whether a recurring series should ever be
  exported as one event with a rule is a calendar decision and is recorded as such in
  the ADR's Consequences.
