package com.singularity.todo.feature.plans.domain.logic

import com.singularity.todo.core.ui.monthAbbreviation
import com.singularity.todo.core.ui.monthName
import com.singularity.todo.core.ui.weekdayAbbreviation
import com.singularity.todo.feature.plans.domain.model.PlansGranularity
import com.singularity.todo.feature.plans.domain.model.TimelinePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
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
 *
 * The names of the months and the weekdays are not spelled out here: they come
 * from the one dictionary in [com.singularity.todo.core.ui], so a timeline
 * heading and a logbook entry cannot name September differently.
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
        else -> "${weekdayAbbreviation(date.dayOfWeek)}, ${date.day} ${monthAbbreviation(date.month)}"
    }

    private fun weekLabel(period: TimelinePeriod): String {
        val start = period.start
        val end = period.end
        return if (start.month == end.month && start.year == end.year) {
            "${start.day}–${end.day} ${monthAbbreviation(start.month)}"
        } else {
            "${start.day} ${monthAbbreviation(start.month)} – ${end.day} ${monthAbbreviation(end.month)}"
        }
    }

    private fun monthLabel(date: LocalDate): String = "${monthName(date.month)} ${date.year}"
}
