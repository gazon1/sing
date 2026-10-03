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

internal fun computeStatistics(
    completedTasks: List<Pair<String, Long>>, // taskId to completedAt epoch
    overdueTasks: List<Pair<String, Long>>, // taskId to dueDate epoch
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
