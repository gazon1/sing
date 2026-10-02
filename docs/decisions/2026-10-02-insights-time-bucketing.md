---
title: "Insights time bucketing: union-merge, midnight split, no SQLite dates on integer columns"
date: 2026-10-02
status: accepted
tags: [insights, timebucketing, database]
---

## Context

MR-5 adds a "Time" tab in Statistics showing "where did time go". Entries are grouped by
day (x-axis) and stacked by project (series). Entries that span midnight must be split
across both days. Overlapping entries (e.g., a one-hour entry and a 90-minute entry that
share 30 minutes) must be merged before summing, so the total is not overstated.

Lotti's insights design says: "Intersections between categories are counted toward each
category. This means the sum per day can exceed wall-clock time for that day — this is
expected for cross-category breakdowns."

## Decision

### `mergeIntervals` — union-merge overlapping / touching intervals

Sort by `startedAt` ascending. Scan left to right: if the next interval starts at or
before the current interval's `endedAt`, extend `current.end` to `max(current.end, next.end)`.
Otherwise, emit `current` and start a new `current`. At end, emit the final `current`.

```kotlin
fun mergeIntervals(entries: List<TimeEntry>): List<TimeEntry> {
    if (entries.size <= 1) return entries
    val sorted = entries.sortedBy { it.startedAt }
    val result = mutableListOf<TimeEntry>()
    var current = sorted.first()
    for (next in sorted.drop(1)) {
        if (next.startedAt <= current.endedAt) {
            current = current.copy(endedAt = maxOf(current.endedAt, next.endedAt))
        } else {
            result.add(current)
            current = next
        }
    }
    result.add(current)
    return result
}
```

This is pure and testable without a database. `TimeEntry` here is an in-memory domain
object (not the entity).

### `splitAtMidnight` — calendar-based midnight split

```kotlin
fun splitAtMidnight(entry: TimeEntry, zone: ZoneId): List<TimeEntry> {
    val startInstant = Instant.fromEpochMilliseconds(entry.startedAt)
    val endInstant = Instant.fromEpochMilliseconds(entry.endedAt ?: clock.now().toEpochMilliseconds())
    val startDay = startInstant.toLocalDate(zone)
    val endDay = endInstant.toLocalDate(zone)
    if (startDay == endDay) return listOf(entry)
    // split at midnight for each day between start and end
    ...
}
```

Uses `kotlin.time.Clock` + `toLocalDate(zone)` (not integer millisecond arithmetic). This
is the correct approach because 23-hour and 25-hour days exist (DST transitions). Adding
24 × 60 × 60 × 1000 ms to a midnight gives the wrong answer on those days. The
`LocalDate` constructor from an `Instant` handles DST correctly.

### `unionDuration` — work-only sum

```kotlin
val TimeEntry.unionDuration: Duration
    get() = Duration.fromEpochMilliseconds(endedAt - startedAt)

fun unionDuration(entries: List<TimeEntry>): Duration =
    entries.filter { it.kind == TimeEntryKind.Work }.sumOf { it.unionDuration }
```

`kind = Recording` entries are excluded from the sum. This is the structural enforcement
of "audio ≠ work" from MR-1.

### Bucket aggregation

```kotlin
data class DayInsightsBucket(
    val date: LocalDate,
    val totalDuration: Duration,
    val byProject: List<ProjectInsightsBucket>,
)

fun bucketByDay(entries: List<TimeEntry>, zone: ZoneId): List<DayInsightsBucket>
fun bucketByProject(entries: List<TimeEntry>): List<ProjectInsightsBucket>
```

`bucketByDay` applies `mergeIntervals` then `splitAtMidnight` before summing.
`bucketByProject` groups by `task.projectId` after the same pre-processing.

### No SQLite date functions on integer columns

All time entry timestamps are stored as epoch millis in `INTEGER` columns. No SQLite
`date()`, `time()`, `julianday()`, or `strftime()` functions are applied to these columns.
Duration is always computed as `ended_at - started_at` in application code (Kotlin).

If a query needs day-level grouping, it reads the epoch millis into the application layer
and converts there with `Instant.fromEpochMilliseconds(...).toLocalDate(zone)`.

## Rationale

The merge-then-split order matters. If entries are split at midnight first and then
merged, the split pieces can create artificial intersections that didn't exist in the
original intervals. By merging first, we compute the true union of overlapping time, then
distribute that union across calendar days.

The DST-safe midnight split (using `LocalDate` from `Instant`) is critical for correctness.
On a "spring forward" day (25-hour day), adding 24 hours to midnight gives 23:00 — a
different calendar day than expected. Using `LocalDate` from the `Instant` directly handles
both 23-hour and 25-hour days correctly.

Counting intersections toward each category (not subtracting overlap) matches Lotti's
design and avoids the complexity of computing exact overlap fractions. The total per day
can exceed 24 hours when multiple projects have overlapping entries — this is intentional
and documented to users.

## Consequences

- `TimeBucketing.kt` is pure domain logic (`commonMain`), testable without a database or
  fake. Tests cover: idempotent merge, DST day (23h and 25h), empty intervals, intervals
  that start and end on the same midnight, `kind = Recording` exclusion.
- The slow contract test (`TimeTrackingRepositoryContractTest`) runs against a real
  `AppDatabaseFactory.build(createSqlDriver(), ":memory:")` with `@Tag("slow")`.
- No lower bound on entry duration. A 1-second interval is included. Lotti's 15-second
  heuristic filter was a noise heuristic, not a semantic property of the time-tracking
  model, and is not reproduced here.

## Links

- `feature/timetracking/domain/logic/TimeBucketing.kt` — `mergeIntervals`, `splitAtMidnight`, `unionDuration`, `bucketByDay`, `bucketByProject`
- `feature/statistics/presentation/insights/InsightsViewModel.kt` — aggregation over `watchEntriesInRange`
- `2026-10-02-task-time-tracking-and-estimate.md` — parent ADR
- ADR 0025 Lotti §2 (Julian day danger, intersection counting)
