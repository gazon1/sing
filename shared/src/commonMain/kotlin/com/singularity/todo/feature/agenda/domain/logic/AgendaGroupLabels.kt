package com.singularity.todo.feature.agenda.domain.logic

import com.singularity.todo.core.ui.monthAbbreviation
import com.singularity.todo.core.ui.monthName
import com.singularity.todo.core.ui.weekdayAbbreviation
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * The header of a date-grouped section.
 *
 * The nearest days are named the way a person says them; anything further out
 * carries the weekday and the date, so a header distinguishes the 12th from the
 * 19th. A week is named by its range, a month by its name and year. The year
 * appears whenever it differs from today's — the Upcoming reach is bounded only
 * by the data, and "12 Nov" alone would be ambiguous a year out.
 *
 * Pure, and in the domain, because the rendered section carries its own label:
 * a section that only knew its period would leave every screen that renders one
 * to work out what to call it.
 */
object AgendaGroupLabels {
    /**
     * The label of one day, as read on [today].
     *
     * Today and Yesterday are kept for a grouped view over past dates; the
     * Upcoming feed starts tomorrow, so it never produces them.
     */
    fun day(
        date: LocalDate,
        today: LocalDate,
    ): String =
        when (date) {
            today -> "Today"
            today.plus(1, DateTimeUnit.DAY) -> "Tomorrow"
            today.minus(1, DateTimeUnit.DAY) -> "Yesterday"
            else -> shortDate(date, includeYear = date.year != today.year)
        }

    /** The label of the Monday-to-Sunday week [start]..[end], as read on [today]. */
    fun week(
        start: LocalDate,
        end: LocalDate,
        today: LocalDate,
    ): String {
        val crossesYear = start.year != end.year
        val sameMonth = start.month == end.month && start.year == end.year
        return when {
            crossesYear -> {
                "${start.day} ${monthAbbreviation(start.month)} ${start.year} – " +
                    "${end.day} ${monthAbbreviation(end.month)} ${end.year}"
            }

            sameMonth -> {
                "${start.day}–${end.day} ${monthAbbreviation(start.month)}" + yearSuffix(start, today)
            }

            else -> {
                "${start.day} ${monthAbbreviation(start.month)} – ${end.day} ${monthAbbreviation(end.month)}" +
                    yearSuffix(start, today)
            }
        }
    }

    /** The label of the calendar month [start] belongs to. */
    fun month(start: LocalDate): String = "${monthName(start.month)} ${start.year}"

    private fun shortDate(
        date: LocalDate,
        includeYear: Boolean,
    ): String {
        val day = "${weekdayAbbreviation(date.dayOfWeek)}, ${date.day} ${monthAbbreviation(date.month)}"
        return if (includeYear) "$day ${date.year}" else day
    }

    private fun yearSuffix(
        periodStart: LocalDate,
        today: LocalDate,
    ): String = if (periodStart.year != today.year) " ${periodStart.year}" else ""
}
