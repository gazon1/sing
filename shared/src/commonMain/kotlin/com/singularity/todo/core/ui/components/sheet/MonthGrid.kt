package com.singularity.todo.core.ui.components.sheet

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus

/**
 * The six-week day grid that covers the month of [anyDateInMonth].
 *
 * ## Why it lives here
 *
 * The grid is needed by every month-shaped date picker, and the calendar feature
 * has one — but a sheet in `core/ui` cannot reach into a feature. The grid is
 * therefore written here, in the layer that every feature can use, and the
 * calendar's own `monthGridDates` delegates to it so that the calendar's public
 * surface and its tests do not move.
 *
 * The grid is always 42 days: six rows of seven, Monday-anchored. A month that
 * fits in five rows still gets a sixth, because a grid that changes its height
 * between months makes the rows under the finger move.
 */
internal fun monthGridDates(anyDateInMonth: LocalDate): List<LocalDate> {
    val firstOfMonth = LocalDate(anyDateInMonth.year, anyDateInMonth.month, 1)
    // Kotlinx-datetime: Monday=0 (ordinal), ..., Sunday=6.
    // We want Mon-anchored grid, so subtract (ordinal - 0) days.
    val gridStart = firstOfMonth.minus(firstOfMonth.dayOfWeek.ordinal, DateTimeUnit.DAY)
    return (0 until 42).map { gridStart.plus(it, DateTimeUnit.DAY) } // 6 weeks × 7 days
}

/**
 * Moves the date by whole months, keeping the day of the month where the
 * month has one.
 *
 * The day is clamped rather than rolled over: stepping from 31 January forward
 * lands on 28 (or 29) February, not on 2 or 3 March. A picker that rolled over
 * would silently move the day the user was looking at.
 */
internal fun shiftMonth(
    date: LocalDate,
    delta: Int,
): LocalDate {
    val total = date.year * 12 + (date.month.number - 1) + delta
    var newYear = total / 12
    var newMonthIndex = total % 12
    if (newMonthIndex < 0) {
        newMonthIndex += 12
        newYear -= 1
    }
    val newMonth = Month(newMonthIndex + 1)
    return LocalDate(newYear, newMonth, minOf(date.day, newMonth.daysIn(newYear)))
}

/** The number of days in [month] of [year]. */
private fun Month.daysIn(year: Int): Int =
    when (this) {
        Month.JANUARY, Month.MARCH, Month.MAY, Month.JULY, Month.AUGUST, Month.OCTOBER, Month.DECEMBER -> 31
        Month.APRIL, Month.JUNE, Month.SEPTEMBER, Month.NOVEMBER -> 30
        Month.FEBRUARY -> if (isLeapYear(year)) 29 else 28
    }

private fun isLeapYear(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
