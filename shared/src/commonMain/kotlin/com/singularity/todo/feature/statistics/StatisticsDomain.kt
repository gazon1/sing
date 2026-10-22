package com.singularity.todo.feature.statistics

import com.singularity.todo.core.platform.TimeConstants
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

data class DayBucket(
    val date: String, // "YYYY-MM-DD"
    val completedCount: Int,
    val overdueCount: Int,
)

data class StatisticsSnapshot(
    val tasksPerDay: List<DayBucket>,
    val totalCompleted: Int,
    val totalOverdue: Int,
    val averagePerDay: Double,
    val computedAt: Long,
)

/**
 * A task as the statistics sees it: the row, and the work it belongs to.
 *
 * @param id The task's own id — the key of a task that stands alone.
 * @param seriesId The series the task belongs to, when it belongs to one.
 * @param completedAt When the task was completed, in epoch millis.
 * @param dueAt The start of the task's due day, in epoch millis.
 */
internal data class StatisticsWorkRow(
    val id: String,
    val seriesId: String?,
    val completedAt: Long?,
    val dueAt: Long?,
)

/**
 * The works the statistics counts, and the day each one belongs to.
 *
 * A series is one piece of work scheduled on several dates, and the statistics
 * measure *works* — a day on which the user finished three things is not a day
 * on which the user finished one thing three times. So the copies of a series
 * count once:
 *
 * - a work that was completed counts once, on the day it was **first**
 *   completed: the work started being done then, and the later copies are the
 *   same work carried on;
 * - a work that was not completed counts as overdue once, on the day of its
 *   **earliest** missed date — and only while nothing of it is completed. A
 *   work that the user has already started is not overdue; it is under way.
 *
 * A task that stands alone is a work of one, and the rules above are its rules.
 *
 * @return The completed works (key to the day they were completed) and the
 *   overdue ones (key to the day they were missed).
 */
internal fun groupIntoWorks(
    rows: List<StatisticsWorkRow>,
): Pair<List<Pair<String, Long>>, List<Pair<String, Long>>> {
    val works = LinkedHashMap<String, MutableList<StatisticsWorkRow>>()
    for (row in rows) {
        works.getOrPut(row.seriesId ?: row.id) { mutableListOf() }.add(row)
    }

    val completed = mutableListOf<Pair<String, Long>>()
    val overdue = mutableListOf<Pair<String, Long>>()
    for ((key, members) in works) {
        val firstCompleted = members.mapNotNull { it.completedAt }.minOrNull()
        if (firstCompleted != null) {
            completed += key to firstCompleted
            continue
        }
        val earliestDue = members.mapNotNull { it.dueAt }.minOrNull()
        if (earliestDue != null) {
            overdue += key to earliestDue
        }
    }
    return completed to overdue
}

internal fun computeStatistics(
    completedTasks: List<Pair<String, Long>>, // workKey to completedAt epoch
    overdueTasks: List<Pair<String, Long>>, // workKey to dueDate epoch
    nowEpochMs: Long,
    rangeDays: Int = 7,
): StatisticsSnapshot {
    val cutoffEpoch = nowEpochMs - (rangeDays * TimeConstants.MILLIS_PER_DAY)
    val recentCompleted = completedTasks.filter { it.second >= cutoffEpoch }
    val recentOverdue = overdueTasks.filter { it.second < nowEpochMs }

    // Build day slots
    val completedPerDay = mutableMapOf<String, Int>()
    val overduePerDay = mutableMapOf<String, Int>()
    for (i in 0 until rangeDays) {
        val dayMs = nowEpochMs - (i * TimeConstants.MILLIS_PER_DAY)
        val day = Instant.fromEpochMilliseconds(dayMs)
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
            .toString()
        completedPerDay[day] = 0
        overduePerDay[day] = 0
    }

    recentCompleted.forEach { (_, completedAt) ->
        val day = Instant.fromEpochMilliseconds(completedAt)
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
            .toString()
        completedPerDay[day] = (completedPerDay[day] ?: 0) + 1
    }

    recentOverdue.forEach { (_, dueAt) ->
        val day = Instant.fromEpochMilliseconds(dueAt)
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
            .toString()
        overduePerDay[day] = (overduePerDay[day] ?: 0) + 1
    }

    val buckets = completedPerDay.keys.sorted().map { date ->
        DayBucket(date, completedPerDay[date] ?: 0, overduePerDay[date] ?: 0)
    }

    return StatisticsSnapshot(
        tasksPerDay = buckets,
        totalCompleted = recentCompleted.size,
        totalOverdue = recentOverdue.size,
        averagePerDay = if (rangeDays > 0) recentCompleted.size.toDouble() / rangeDays else 0.0,
        computedAt = nowEpochMs,
    )
}
