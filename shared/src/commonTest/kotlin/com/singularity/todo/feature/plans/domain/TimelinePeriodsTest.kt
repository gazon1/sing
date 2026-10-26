package com.singularity.todo.feature.plans.domain

import com.singularity.todo.feature.plans.domain.logic.TimelinePeriods
import com.singularity.todo.feature.plans.domain.model.PlansGranularity
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.plus
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the periods a granularity produces.
 *
 * The periods of one granularity must be pairwise disjoint — that is what makes
 * "a task is in exactly one section" structural rather than a rule to
 * remember — and they must cover the horizon the granularity promises.
 */
@Tag("fast")
class TimelinePeriodsTest {

    // Wednesday 2026-09-16.
    private val today = LocalDate(2026, Month.SEPTEMBER, 16)

    @Test
    fun `a day granularity covers thirty days from today`() {
        val periods = TimelinePeriods.forGranularity(PlansGranularity.Day, today)

        assertEquals(30, periods.size)
        assertEquals(today, periods.first().start)
        assertEquals(today.plus(29, DateTimeUnit.DAY), periods.last().end)
    }

    @Test
    fun `a day period is one day`() {
        val period = TimelinePeriods.forGranularity(PlansGranularity.Day, today).first()

        assertEquals(period.start, period.end)
        assertEquals("day:2026-09-16", period.id)
    }

    @Test
    fun `a week period runs Monday to Sunday`() {
        val period = TimelinePeriods.forGranularity(PlansGranularity.Week, today).first()

        assertEquals(LocalDate(2026, Month.SEPTEMBER, 14), period.start)
        assertEquals(LocalDate(2026, Month.SEPTEMBER, 20), period.end)
    }

    @Test
    fun `a week granularity covers twelve weeks`() {
        val periods = TimelinePeriods.forGranularity(PlansGranularity.Week, today)

        assertEquals(12, periods.size)
        assertEquals(LocalDate(2026, Month.SEPTEMBER, 14), periods.first().start)
    }

    @Test
    fun `a month period is a calendar month`() {
        val period = TimelinePeriods.forGranularity(PlansGranularity.Month, today).first()

        assertEquals(LocalDate(2026, Month.SEPTEMBER, 1), period.start)
        assertEquals(LocalDate(2026, Month.SEPTEMBER, 30), period.end)
    }

    @Test
    fun `a month granularity covers twelve months`() {
        val periods = TimelinePeriods.forGranularity(PlansGranularity.Month, today)

        assertEquals(12, periods.size)
        assertEquals(LocalDate(2026, Month.SEPTEMBER, 1), periods.first().start)
        assertEquals(LocalDate(2027, Month.AUGUST, 31), periods.last().end)
    }

    @Test
    fun `the periods of a granularity are disjoint and ordered`() {
        for (granularity in PlansGranularity.entries) {
            val periods = TimelinePeriods.forGranularity(granularity, today)

            periods.zipWithNext { earlier, later ->
                assertTrue(
                    earlier.end < later.start,
                    "$granularity periods must not overlap: $earlier then $later",
                )
            }
        }
    }

    @Test
    fun `the horizon end is the end of the last period`() {
        for (granularity in PlansGranularity.entries) {
            val periods = TimelinePeriods.forGranularity(granularity, today)
            assertEquals(periods.last().end, TimelinePeriods.horizonEnd(granularity, today))
        }
    }
}
