# Tasks — task-series-presentation

## Phase 1 — the mark

- [x] **shared/**: `SeriesMarker` — one component, two shapes: the icon alone
      (compact) and the icon with the count.
      Test: the component compiles into the day's list and into the calendar's
      chip; the mark carries a test tag.
- [x] **shared/**: the day's list — the row carries the number of dates of its
      series, counted once for the whole task list.
      Test: the agenda's evaluator test — a row of a series carries the number
      of dates, a task that stands alone carries none.
- [x] **shared/**: the calendar — the chip carries the mark.
      Test: the mapper test — a task of a series maps to a chip of a series.

## Phase 2 — the undo

- [x] **shared/**: `archiveSeries` returns the ids it archived.
      Test: `UpdateTaskSeriesUseCaseTest` — the archive returns both members.
- [x] **shared/**: `DeleteSeries` and `RestoreSeries` are lifecycle intents,
      and the lifecycle slot owns them.
      Test: `TaskLifecycleSlotTest` — deleting a series archives every
      occurrence and offers the undo with the ids; the undo puts them back.
- [x] **shared/**: the screen shows the undo, with the number of dates.
      Test: the notification carries the count and the action.

## Phase 3 — the statistics

- [x] **shared/**: `groupIntoWorks` — the rule that counts works.
      Test: `StatisticsWorksTest` — a series counts once; the day is the first
      completion; an untouched series is overdue once, on its earliest date; a
      started series is not overdue.
- [x] **shared/**: the statistics view model groups the rows before the
      buckets are counted.
      Test: the statistics view model test — a series contributes one work.

## Phase 4 — the gates

- [x] **shared/**: detekt, the JVM tests, the coverage floor and the static
      gates pass.
      Test: the CI run on the branch is green.
- [x] **shared/**: the issue registry carries this change.
      Test: `python3 scripts/check-gate-registries.py` passes.
