package com.singularity.todo.feature.statistics

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The chart draws one bar per day of the range the user selected, so the
 * snapshot has to carry that many days.
 *
 * The snapshot used to be computed at a fixed seven days whatever the range, so
 * picking "30d" left a seven-bar chart under a "Last 7 days" title — the chart
 * and the range selector described two different windows.
 */
@Tag("fast")
class StatisticsRangeTest {
    private val now: Long = Instant.parse("2026-01-15T12:00:00Z").toEpochMilliseconds()

    @Test
    fun `the snapshot carries one bucket per day of the requested range`() {
        for (rangeDays in listOf(7, 30, 90)) {
            val snapshot = computeStatistics(emptyList(), emptyList(), now, rangeDays)

            assertEquals(rangeDays, snapshot.tasksPerDay.size, "$rangeDays days")
        }
    }

    @Test
    fun `a completed work lands on the day it was completed, inside a thirty day range`() {
        val snapshot = computeStatistics(listOf("t1" to now), emptyList(), now, 30)

        assertEquals(30, snapshot.tasksPerDay.size)
        assertEquals(1, snapshot.tasksPerDay.last().completedCount, "the newest bucket is today")
        assertEquals(1, snapshot.totalCompleted)
    }

    @Test
    fun `the average is taken over the selected range, not over a week`() {
        val snapshot = computeStatistics(listOf("t1" to now), emptyList(), now, 30)

        assertEquals(1.0 / 30, snapshot.averagePerDay)
    }
}
