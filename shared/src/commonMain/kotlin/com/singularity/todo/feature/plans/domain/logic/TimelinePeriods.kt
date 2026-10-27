package com.singularity.todo.feature.plans.domain.logic

import com.singularity.todo.feature.plans.domain.model.PlansGranularity
import com.singularity.todo.feature.plans.domain.model.TimelinePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * The periods a granularity produces, starting from today.
 *
 * A week runs Monday to Sunday, matching the app's existing relative week
 * buckets, and a month is a calendar month. The periods of one granularity are
 * pairwise disjoint and sorted, which is what lets the grouper put a task in
 * exactly one section without a discard flag.
 *
 * Pure: the date is a parameter, never the wall clock.
 */
object TimelinePeriods {

    /**
     * The periods [granularity] shows, starting at [today].
     *
     * The first period contains today — a day granularity begins with today,
     * a week granularity with the week today belongs to, a month granularity
     * with the month today belongs to.
     */
    fun forGranularity(granularity: PlansGranularity, today: LocalDate): List<TimelinePeriod> =
        when (granularity) {
            PlansGranularity.Day -> dayPeriods(today, granularity.periodCount)
            PlansGranularity.Week -> weekPeriods(today, granularity.periodCount)
            PlansGranularity.Month -> monthPeriods(today, granularity.periodCount)
        }

    /** The last day covered by the periods of [granularity] starting at [today]. */
    fun horizonEnd(granularity: PlansGranularity, today: LocalDate): LocalDate =
        forGranularity(granularity, today).last().end

    private fun dayPeriods(today: LocalDate, count: Int): List<TimelinePeriod> =
        (0 until count).map { offset ->
            val day = today.plus(offset, DateTimeUnit.DAY)
            TimelinePeriod(id = "day:$day", start = day, end = day)
        }

    private fun weekPeriods(today: LocalDate, count: Int): List<TimelinePeriod> {
        val startOfThisWeek = today.minus(today.dayOfWeek.ordinal, DateTimeUnit.DAY)
        return (0 until count).map { offset ->
            val start = startOfThisWeek.plus(offset * 7, DateTimeUnit.DAY)
            TimelinePeriod(id = "week:$start", start = start, end = start.plus(6, DateTimeUnit.DAY))
        }
    }

    private fun monthPeriods(today: LocalDate, count: Int): List<TimelinePeriod> {
        val startOfThisMonth = LocalDate(today.year, today.month, 1)
        return (0 until count).map { offset ->
            val start = startOfThisMonth.plus(offset, DateTimeUnit.MONTH)
            val nextMonth = start.plus(1, DateTimeUnit.MONTH)
            val end = LocalDate(nextMonth.year, nextMonth.month, 1).minus(1, DateTimeUnit.DAY)
            TimelinePeriod(id = "month:${start.year}-${start.monthNumber}", start = start, end = end)
        }
    }
}
