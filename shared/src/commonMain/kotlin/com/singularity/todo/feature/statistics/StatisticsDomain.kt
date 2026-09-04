package com.singularity.todo.feature.statistics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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

data class Statistics(
    val snapshot: StatisticsSnapshot,
)

internal fun computeStatistics(
    completedTasks: List<Pair<String, Long>>, // taskId to completedAt epoch
    overdueTasks: List<Pair<String, Long>>,    // taskId to dueDate epoch
    nowEpochMs: Long,
    rangeDays: Int = 7,
): StatisticsSnapshot {
    val cutoffEpoch = nowEpochMs - (rangeDays * 24 * 60 * 60 * 1000L)
    val recentCompleted = completedTasks.filter { it.second >= cutoffEpoch }
    val recentOverdue = overdueTasks.filter { it.second < nowEpochMs }

    // Group by day
    val perDayMap = mutableMapOf<String, Int>()
    for (i in 0 until rangeDays) {
        val dayMs = nowEpochMs - (i * 24 * 60 * 60 * 1000L)
        val day = Instant.ofEpochMilli(dayMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate().toString()
        perDayMap[day] = 0
    }

    recentCompleted.forEach { (_, completedAt) ->
        val day = Instant.ofEpochMilli(completedAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDate().toString()
        perDayMap[day] = (perDayMap[day] ?: 0) + 1
    }

    val buckets = perDayMap.entries
        .sortedBy { it.key }
        .map { (date, count) -> DayBucket(date, count, 0) }

    return StatisticsSnapshot(
        tasksPerDay = buckets,
        totalCompleted = recentCompleted.size,
        totalOverdue = recentOverdue.size,
        averagePerDay = if (rangeDays > 0) recentCompleted.size.toDouble() / rangeDays else 0.0,
        computedAt = nowEpochMs,
    )
}
