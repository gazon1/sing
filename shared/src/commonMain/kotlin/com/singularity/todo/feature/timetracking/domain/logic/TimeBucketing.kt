package com.singularity.todo.feature.timetracking.domain.logic

import com.singularity.todo.core.ids.TimeEntryId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.TimeConstants
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.timetracking.domain.TimeEntry
import com.singularity.todo.feature.timetracking.domain.TimeEntryKind
import com.singularity.todo.feature.timetracking.domain.TimeEntrySource
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

// ─── Interval representation ──────────────────────────────────────────────────

/**
 * A half-open time interval: `[start, end)`, where `start < end`.
 * All timestamps are epoch millis.
 */
data class TimeInterval(
    val startMs: Long,
    val endMs: Long, // exclusive
) {
    init {
        require(endMs > startMs) { "endMs ($endMs) must be > startMs ($startMs)" }
    }

    /** Duration of this interval in milliseconds. */
    val durationMs: Long get() = endMs - startMs
}

/**
 * An open (ongoing) interval with no end — the timer is still running.
 */
data class OpenInterval(val startMs: Long)

// ─── Merge ───────────────────────────────────────────────────────────────────

/**
 * Merges a list of [TimeEntry] intervals by union — overlapping or adjacent intervals
 * are collapsed into a single interval spanning from the earliest start to the latest end.
 *
 * The algorithm:
 * 1. Sort by `startMs` ascending.
 * 2. Iterate, merging whenever the next interval starts at or before the current end
 *    (touching at the boundary counts as a merge — union semantics).
 *
 * ## Invariant
 *
 * Uses **union semantics** (ADR 0025 Lotti): merged intervals represent the total
 * elapsed wall-clock time covered, not the sum of independent sessions.
 * Intersections between project categories count toward each category's total.
 *
 * @param entries The time entries to merge.
 * @return A list of merged [TimeInterval]s, sorted by `startMs` ascending.
 */
fun mergeIntervals(entries: List<TimeEntry>): List<TimeInterval> {
    if (entries.isEmpty()) return emptyList()

    // Filter out Recording entries — they don't count as work
    val workEntries = entries.filter { it.kind == TimeEntryKind.Work && it.endedAt != null }
    if (workEntries.isEmpty()) return emptyList()

    // Build closed intervals from entries with endedAt
    val intervals = workEntries.map { entry ->
        TimeInterval(
            startMs = entry.startedAt.toEpochMilliseconds(),
            endMs = entry.endedAt!!.toEpochMilliseconds(),
        )
    }.sortedBy { it.startMs }

    val result = mutableListOf<TimeInterval>()
    var current = intervals.first()

    for (i in 1 until intervals.size) {
        val next = intervals[i]
        if (next.startMs <= current.endMs) {
            // Overlap or touch — extend current
            current = TimeInterval(current.startMs, maxOf(current.endMs, next.endMs))
        } else {
            // Gap — emit current and start new
            result.add(current)
            current = next
        }
    }
    result.add(current)
    return result
}

/**
 * Like [mergeIntervals] but includes an open (running) entry.
 * The open interval's end is taken as `nowMs` at call time.
 */
fun mergeIntervalsWithOpen(entries: List<TimeEntry>, open: OpenInterval?, nowMs: Long): List<TimeInterval> {
    if (entries.isEmpty() && open == null) return emptyList()

    val allIntervals = mutableListOf<TimeInterval>()

    entries
        .filter { it.kind == TimeEntryKind.Work && it.endedAt != null }
        .forEach { entry ->
            allIntervals.add(
                TimeInterval(
                    entry.startedAt.toEpochMilliseconds(),
                    entry.endedAt!!.toEpochMilliseconds(),
                ),
            )
        }

    open?.let { allIntervals.add(TimeInterval(it.startMs, nowMs)) }

    // Synthesize pseudo-entries for merge algorithm
    val pseudoEntries = allIntervals.map { interval ->
        TimeEntry(
            id = TimeEntryId("synth-${interval.startMs}"),
            taskId = TaskId(""),
            userId = UserId(""),
            startedAt = Instant.fromEpochMilliseconds(interval.startMs),
            endedAt = Instant.fromEpochMilliseconds(interval.endMs),
            kind = TimeEntryKind.Work,
            source = TimeEntrySource.Timer,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
        )
    }

    return mergeIntervals(pseudoEntries)
}

// ─── Midnight splitting ───────────────────────────────────────────────────────

/**
 * Splits an interval at each local midnight boundary it crosses.
 *
 * Uses the calendar constructor `LocalDate(date).atStartOfDayIn(zone)` rather than
 * arithmetic — this correctly handles 23-hour and 25-hour DST transition days.
 *
 * @param interval The interval to split.
 * @param zone The time zone for computing midnight.
 * @return A list of sub-intervals, none of which cross midnight.
 */
fun splitAtMidnight(interval: TimeInterval, zone: TimeZone): List<TimeInterval> {
    val result = mutableListOf<TimeInterval>()
    var cursor = interval.startMs

    while (cursor < interval.endMs) {
        val cursorDate = Instant.fromEpochMilliseconds(cursor)
            .toLocalDateTime(zone).date

        val midnightTomorrow = cursorDate
            .plus(1, DateTimeUnit.DAY)
            .atStartOfDayIn(zone)
            .toEpochMilliseconds()

        val endOfSlice = minOf(midnightTomorrow, interval.endMs)
        result.add(TimeInterval(cursor, endOfSlice))
        cursor = endOfSlice
    }
    return result
}

// ─── Aggregation ─────────────────────────────────────────────────────────────

/**
 * The progress of a task: total work time and fraction of estimate completed.
 *
 * @param totalWorkMs Total merged work time in milliseconds.
 * @param estimateMinutes The task's estimate, or null if not set.
 */
data class TaskProgress(val totalWorkMs: Long, val estimateMinutes: Int?) {
    /** Progress as a fraction in 0.0..1.0, or null if no estimate. */
    val fraction: Double? get() = estimateMinutes?.let { est ->
        if (est <= 0) {
            null
        } else {
            (totalWorkMs / 60_000.0 / est).coerceIn(0.0, 1.0)
        }
    }

    val isOverEstimate: Boolean get() = estimateMinutes != null && fraction != null && fraction!! > 1.0
}

/**
 * Aggregates a list of time entries into total work time for a single task.
 *
 * - Filters out [TimeEntryKind.Recording] entries (they are not "work").
 * - Merges overlapping intervals using union semantics.
 * - Splits at midnight to handle multi-day sessions correctly.
 * - Returns [TaskProgress] with the total and fraction of estimate.
 *
 * @param entries All time entries for the task.
 * @param estimateMinutes The task's estimate, or null.
 * @param zone Time zone for midnight splitting.
 */
fun taskProgress(entries: List<TimeEntry>, estimateMinutes: Int?, zone: TimeZone): TaskProgress {
    val workEntries = entries.filter { it.kind == TimeEntryKind.Work }
    if (workEntries.isEmpty()) return TaskProgress(0, estimateMinutes)

    val merged = mergeIntervals(workEntries)
    val split = merged.flatMap { splitAtMidnight(it, zone) }
    val totalMs = split.sumOf { it.durationMs }

    return TaskProgress(totalMs, estimateMinutes)
}

/**
 * Groups time entries by calendar day (YYYY-MM-DD).
 *
 * @param entries Entries to group.
 * @param zone Time zone for computing the date of each entry.
 * @return Map from date string to entries on that day.
 */
fun groupByDay(entries: List<TimeEntry>, zone: TimeZone): Map<String, List<TimeEntry>> = entries
    .filter { it.kind == TimeEntryKind.Work && it.endedAt != null }
    .groupBy { entry ->
        entry.startedAt
            .toLocalDateTime(zone)
            .date
            .toString()
    }

/**
 * A day bucket for the Insights time-series chart.
 *
 * @property date YYYY-MM-DD string.
 * @property totalMs Total merged work time on this day in milliseconds.
 */
data class DayInsightsBucket(val date: String, val totalMs: Long)

/**
 * Buckets pre-split [TimeInterval]s by calendar day, filling in missing days with zero.
 *
 * @param intervals Intervals to bucket (already split at midnight).
 * @param nowMs Current epoch millis — used to determine the range.
 * @param rangeDays Number of past days to include (including today).
 * @param zone Time zone for computing calendar dates.
 */
fun bucketByDay(intervals: List<TimeInterval>, nowMs: Long, rangeDays: Int, zone: TimeZone): List<DayInsightsBucket> {
    val cutoffMs = nowMs - (rangeDays * TimeConstants.MILLIS_PER_DAY)

    // Build all days in range
    val dayMap = mutableMapOf<String, Long>()
    for (i in 0 until rangeDays) {
        val dayMs = nowMs - (i * TimeConstants.MILLIS_PER_DAY)
        val date = Instant.fromEpochMilliseconds(dayMs)
            .toLocalDateTime(zone).date
            .toString()
        dayMap[date] = 0L
    }

    // Sum durations per day from intervals
    intervals.forEach { interval ->
        // interval is guaranteed not to cross midnight (splitAtMidnight guarantees this)
        val date = Instant.fromEpochMilliseconds(interval.startMs)
            .toLocalDateTime(zone).date
            .toString()
        if (date in dayMap) {
            dayMap[date] = dayMap[date]!! + interval.durationMs
        }
    }

    return dayMap.entries
        .sortedBy { it.key }
        .map { (date, ms) -> DayInsightsBucket(date, ms) }
}

/**
 * Groups time entries by project.
 *
 * @param entries Entries to group.
 * @param taskProjectMap Map from taskId to projectId (or null for no project).
 * @return Map from projectId (or null for no project) to entries.
 */
fun groupByProject(entries: List<TimeEntry>, taskProjectMap: Map<String, String?>): Map<String?, List<TimeEntry>> =
    entries
        .filter { it.kind == TimeEntryKind.Work && it.endedAt != null }
        .groupBy { entry -> taskProjectMap[entry.taskId.value] }

/**
 * Aggregates [TimeEntry] durations by project, returning total milliseconds per project.
 *
 * @param entries Entries to aggregate (should be unsplit — project totals are correct without midnight splitting).
 * @param taskProjectMap Map from taskId to projectId (or null for no project).
 * @return Map from projectId (or null) to total milliseconds.
 */
fun bucketByProject(entries: List<TimeEntry>, taskProjectMap: Map<String, String?>): Map<String?, Long> {
    val grouped = groupByProject(entries, taskProjectMap)
    return grouped.mapValues { (_, groupEntries) ->
        groupEntries.sumOf { entry ->
            val start = entry.startedAt.toEpochMilliseconds()
            val end = entry.endedAt!!.toEpochMilliseconds()
            end - start
        }
    }
}
