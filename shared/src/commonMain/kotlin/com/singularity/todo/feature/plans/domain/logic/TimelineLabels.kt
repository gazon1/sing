package com.singularity.todo.feature.plans.domain.logic

import com.singularity.todo.feature.plans.domain.model.PlansGranularity
import com.singularity.todo.feature.plans.domain.model.TimelinePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.plus

/**
 * What a section header says.
 *
 * The header is the only thing that tells the reader which period the rows
 * under it belong to, so it names the period in full — the date for a day, the
 * range for a week, the month and the year for a month.
 *
 * Pure, and in the domain, because the section carries its own label: a
 * section that only knew its dates would leave every screen that renders one
 * to work out what to call it.
 */
object TimelineLabels {

    /** The name of the section that holds the work that is already late. */
    const val OVERDUE: String = "Overdue"

    /** The label of the period [period] covers, given [today]. */
    fun forPeriod(
        granularity: PlansGranularity,
        period: TimelinePeriod,
        today: LocalDate,
    ): String = when (granularity) {
        PlansGranularity.Day -> dayLabel(period.start, today)
        PlansGranularity.Week -> weekLabel(period)
        PlansGranularity.Month -> monthLabel(period.start)
    }

    private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.plus(1, DateTimeUnit.DAY) -> "Tomorrow"
        else -> "${shortWeekday(date.dayOfWeek)}, ${date.day} ${shortMonth(date.month)}"
    }

    private fun weekLabel(period: TimelinePeriod): String {
        val start = period.start
        val end = period.end
        return if (start.month == end.month && start.year == end.year) {
            "${start.day}–${end.day} ${shortMonth(start.month)}"
        } else {
            "${start.day} ${shortMonth(start.month)} – ${end.day} ${shortMonth(end.month)}"
        }
    }

    private fun monthLabel(date: LocalDate): String = "${longMonth(date.month)} ${date.year}"

    private fun shortWeekday(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "Mon"
        DayOfWeek.TUESDAY -> "Tue"
        DayOfWeek.WEDNESDAY -> "Wed"
        DayOfWeek.THURSDAY -> "Thu"
        DayOfWeek.FRIDAY -> "Fri"
        DayOfWeek.SATURDAY -> "Sat"
        DayOfWeek.SUNDAY -> "Sun"
    }

    private fun shortMonth(month: Month): String = when (month) {
        Month.JANUARY -> "Jan"
        Month.FEBRUARY -> "Feb"
        Month.MARCH -> "Mar"
        Month.APRIL -> "Apr"
        Month.MAY -> "May"
        Month.JUNE -> "Jun"
        Month.JULY -> "Jul"
        Month.AUGUST -> "Aug"
        Month.SEPTEMBER -> "Sep"
        Month.OCTOBER -> "Oct"
        Month.NOVEMBER -> "Nov"
        Month.DECEMBER -> "Dec"
    }

    private fun longMonth(month: Month): String = when (month) {
        Month.JANUARY -> "January"
        Month.FEBRUARY -> "February"
        Month.MARCH -> "March"
        Month.APRIL -> "April"
        Month.MAY -> "May"
        Month.JUNE -> "June"
        Month.JULY -> "July"
        Month.AUGUST -> "August"
        Month.SEPTEMBER -> "September"
        Month.OCTOBER -> "October"
        Month.NOVEMBER -> "November"
        Month.DECEMBER -> "December"
    }
}
