package com.singularity.todo.feature.statistics

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The statistics count works, and a series is one work.
 *
 * The rule under test: the copies of a series count once — as completed on the
 * day the work was first completed, and as overdue once, on the day of its
 * earliest missed date, and only while nothing of it is completed.
 */
@Tag("fast")
class StatisticsWorksTest {

    private fun row(
        id: String,
        seriesId: String? = null,
        completedAt: Long? = null,
        dueAt: Long? = null,
    ) = StatisticsWorkRow(id = id, seriesId = seriesId, completedAt = completedAt, dueAt = dueAt)

    @Test
    fun `a task that stands alone is a work of one`() {
        val (completed, overdue) = groupIntoWorks(listOf(row(id = "t1", completedAt = 100)))

        assertEquals(listOf("t1" to 100L), completed)
        assertTrue(overdue.isEmpty())
    }

    @Test
    fun `a series that was completed counts once`() {
        val rows = listOf(
            row(id = "t1", seriesId = "s1", completedAt = 100),
            row(id = "t2", seriesId = "s1", completedAt = 200),
            row(id = "t3", seriesId = "s1", completedAt = 300),
        )

        val (completed, overdue) = groupIntoWorks(rows)

        assertEquals(1, completed.size, "three copies are one work")
        assertEquals("s1", completed.first().first)
    }

    @Test
    fun `a completed series is counted on the day the work was first completed`() {
        val rows = listOf(
            row(id = "t1", seriesId = "s1", completedAt = 300),
            row(id = "t2", seriesId = "s1", completedAt = 100),
            row(id = "t3", seriesId = "s1", completedAt = 200),
        )

        val (completed, _) = groupIntoWorks(rows)

        assertEquals(100L, completed.first().second, "the work started being done on the first day")
    }

    @Test
    fun `a series that was not completed counts as overdue once, on its earliest date`() {
        val rows = listOf(
            row(id = "t1", seriesId = "s1", dueAt = 300),
            row(id = "t2", seriesId = "s1", dueAt = 100),
            row(id = "t3", seriesId = "s1", dueAt = 200),
        )

        val (completed, overdue) = groupIntoWorks(rows)

        assertTrue(completed.isEmpty())
        assertEquals(1, overdue.size, "three missed dates are one missed work")
        assertEquals(100L, overdue.first().second)
    }

    @Test
    fun `a series that was started is not overdue`() {
        val rows = listOf(
            row(id = "t1", seriesId = "s1", completedAt = 100),
            row(id = "t2", seriesId = "s1", dueAt = 200),
        )

        val (_, overdue) = groupIntoWorks(rows)

        assertTrue(overdue.isEmpty(), "a work the user has started is under way, not overdue")
    }

    @Test
    fun `two series are two works`() {
        val rows = listOf(
            row(id = "t1", seriesId = "s1", completedAt = 100),
            row(id = "t2", seriesId = "s2", completedAt = 200),
        )

        val (completed, _) = groupIntoWorks(rows)

        assertEquals(2, completed.size)
    }

    @Test
    fun `a series and a task that stands alone are two works`() {
        val rows = listOf(
            row(id = "t1", seriesId = "s1", completedAt = 100),
            row(id = "t2", completedAt = 200),
        )

        val (completed, _) = groupIntoWorks(rows)

        assertEquals(2, completed.size)
    }

    @Test
    fun `a work without a completion and without a date is counted nowhere`() {
        val (completed, overdue) = groupIntoWorks(listOf(row(id = "t1", seriesId = "s1")))

        assertTrue(completed.isEmpty())
        assertTrue(overdue.isEmpty())
    }
}
