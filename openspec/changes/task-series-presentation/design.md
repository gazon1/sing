# task-series-presentation — design

## The shape of the change

Three behaviours, three places, one rule each:

```
SeriesMarker (core/ui)          the mark: an icon, and the count where there is room
  ├── TaskMetaRow                the day's list — the icon and the count
  └── TaskChip                   the calendar — the icon alone

TaskLifecycleSlot                the undo of a series deletion
  ├── DeleteSeries               archives every occurrence, returns their ids
  └── RestoreSeries(ids)         restores exactly those ids

groupIntoWorks (statistics)      the rule that counts works
```

## Why the mark is one component

The calendar and the day's list are two surfaces that show the same fact — this
row is one date of a work — and they have different amounts of room. Two
implementations would drift: one would gain a word, the other would lose the
count, and the two would stop saying the same thing.

So there is one component with two shapes: `compact` draws the icon alone, and
the full shape draws the icon and the count. The surfaces choose; the component
decides what the mark says.

## Where the count comes from

The agenda evaluates its sections against the whole task list, so the number of
dates of a series is counted once for the list — `groupingBy { it }.eachCount()`
over the series ids — and the row reads its number from that table.

The calendar does not show the count: a chip has no room for it, and the chip's
job is to say what the row is, not how much of it is left.

## Why the undo carries ids

`archiveSeries` reads the members of the series, archives each of them, and
returns the ids it archived. The undo restores exactly those ids.

The alternative — "restore the members of the series" — restores whatever the
series is at the time of the undo. A date the user added in between would come
back with the work; a date they removed would stay away. Both are wrong, and
both are silent.

The ids travel in the intent (`RestoreSeries(taskIds)`), so the slot does not
have to keep a snapshot: the notification holds them until the user acts.

## Why the series deletion navigates back

The screen was open on a task that no longer exists. Archiving one occurrence
already navigates back; archiving the whole series is the same situation with
more rows.

## Why the statistics group before counting

The buckets are counted from two lists: the completed works and the overdue
ones. If the grouping happened *inside* the bucketing, a bucket could see two
copies of the same work — the first copy in the bucket and the second one
counted as another job.

So the grouping is a step before: `groupIntoWorks` turns the rows into works,
and `computeStatistics` counts the works it is handed. The rule lives in one
pure function, and its test says what the rule is rather than what the screen
shows.

The rule itself:

- a work's completion day is the **first** completion among its occurrences;
- a work with no completion is overdue on the **earliest** of its missed dates;
- a work with a completion is never overdue.

## Testing

- `StatisticsWorksTest` — the rule: a series counts once, on the day it was
  first completed; an untouched series is overdue once, on its earliest date; a
  started series is not overdue.
- `TaskLifecycleSlotTest` — deleting a series archives every occurrence and
  offers the undo with the ids; the undo puts them back.
- `UpdateTaskSeriesUseCaseTest` — the archive returns the ids it archived.
- The agenda's own tests — the evaluator counts the dates of a series once.
