# task-series — design

## The shape

A series is **not** a table. It is the set of task rows that share one
`TaskSeriesId`. The only new storage is a nullable `series_id` column on `tasks`,
added by the auto-migration 44 → 45.

```
tasks
├── id, title, due_date, …          (unchanged)
└── series_id  (nullable)  ──────────┐
                                      │
       three rows, one id ────────────┘
```

`TaskSeriesId` is a `@JvmInline value class` over a `String`, like every other id in
the codebase.

## The scope rule

`TaskSharedField` lists the fields that describe the *work*: title, description,
priority, project, tags, accent, emoji, estimate, recurrence. Everything outside the
list describes the *occurrence*: the date, the time of day, the start and end window,
the completion state, the pin, the checklist, the attachments, the reminders, the
dependencies.

`sharedFieldOf(intent)` — one function, in
`feature/tasks/presentation/viewmodel/slot/TaskSharedFieldScope.kt` — maps an intent
to a field or to nothing. Two slots use it: the entity slot (priority, project,
tags, recurrence, estimate) and the draft slot (title, description).

A field in the list is written to the whole series, in one transaction. A field
outside it is written to the occurrence on screen.

Two fields sit outside the list on purpose: the **time of day** and the **pin**.
"The same on every date" is a choice the user makes for those, so they get an
explicit action — "Применить ко всем" on the series card — rather than a rule.

## The write path

```
TaskSeriesPlanner.plan(base, dates, seriesId, now, userId) → List<Task>
        │
        ├── dates.size == 1  → one ordinary task, seriesId = null
        ├── dates.size > 1   → one task per date, all sharing one seriesId
        └── dates.isEmpty()  → one undated task
```

`TaskRepository.createSeries` writes the rows in one transaction. `updateAll` does
the same for an edit, with the same read-before-write guard the single-row update
has. A series that landed halfway would be a set of rows that share an id and
describe different work, and no reader could tell.

`UpdateTaskSeriesUseCase` is the read-modify-write of a whole series: it reads the
members *now*, applies the change to each, and writes them back together.

## The three entry points

| Screen | Gesture | Draft key |
|---|---|---|
| Create editor | the date row opens the multi-date picker | — (the editor's own draft) |
| Calendar | long press on a day that holds tasks → tap more days → "Создать задачу" | `TaskDraftKeys.CALENDAR_MULTI_DATE` |
| Agenda | the top-bar button opens the multi-date picker | `TaskDraftKeys.AGENDA_MULTI_DATE` |

The calendar and the agenda write the picked days into a `DraftStore` draft and point
the editor at it by key. The days travel in the draft, not in a route parameter: a
list of dates does not belong in a back stack, and the draft survives the death of
the process.

`TaskDraftKeys` is the one place the keys are built. The key is passed to the editor
complete — the editor does not know where a draft came from, and a key it has to
rebuild is a key that can be rebuilt wrongly.

## The ceiling

Thirty dates. The check lives in `TaskSeriesPlanner`, in the domain, so it holds for
every entry point and not only for the picker that happens to show the message. The
picker refuses the click and says why: a silent cap would let the user believe they
picked forty dates and then discover that thirty survived.

## The draft's shape

`TaskDraft.dueDate` stays a `DueDateOption` so that "Today" and "Tomorrow" keep
meaning the day of the *save*, not the day the draft was first written. The extra
dates ride along in `TaskDraft.extraDueDates` as concrete `LocalDate`s, because
there is no relative form for a day the user picked in a grid.

## What the series looks like elsewhere

Nowhere special. A series is several tasks, so the agenda shows three rows, the
calendar shows three events, the backup writes three rows, and the search finds all
three. The only screen that treats a series differently is the task detail, which
shows the series card.
