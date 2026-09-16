package com.singularity.todo.feature.calendar.domain.logic

import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Pure date arithmetic for the Calendar screen.
 * All functions are side-effect free and fully unit-testable.
 */

/**
 * Returns the full 6-week grid for a month view, including trailing days from
 * adjacent months (Mon-anchored), starting from the Monday before the 1st of
 * the month (or on the 1st itself if it is a Monday).
 */
fun monthGridDates(anyDateInMonth: LocalDate): List<LocalDate> {
    val firstOfMonth = LocalDate(anyDateInMonth.year, anyDateInMonth.month, 1)
    // Kotlinx-datetime: Monday=0 (ordinal), ..., Sunday=6.
    // We want Mon-anchored grid, so subtract (ordinal - 0) days.
    val gridStart = firstOfMonth.minus(firstOfMonth.dayOfWeek.ordinal, DateTimeUnit.DAY)
    return (0 until 42).map { gridStart.plus(it, DateTimeUnit.DAY) } // 6 weeks × 7 days
}

/** Returns the visible date range for the given [viewMode] and [anchor] date. */
fun visibleRange(anchor: LocalDate, viewMode: CalendarViewMode): List<LocalDate> = when (viewMode) {
    CalendarViewMode.DAY -> listOf(anchor)
    CalendarViewMode.FOUR_DAYS -> (0..3).map { anchor.plus(it, DateTimeUnit.DAY) }
    CalendarViewMode.WEEK -> {
        val weekStart = anchor.minus(anchor.dayOfWeek.ordinal, DateTimeUnit.DAY)
        (0..6).map { weekStart.plus(it, DateTimeUnit.DAY) }
    }
    CalendarViewMode.MONTH -> monthGridDates(anchor)
}

/** Advances [date] by one step in the given [viewMode]. */
fun goNext(date: LocalDate, viewMode: CalendarViewMode): LocalDate = when (viewMode) {
    CalendarViewMode.DAY -> date.plus(1, DateTimeUnit.DAY)
    CalendarViewMode.FOUR_DAYS -> date.plus(4, DateTimeUnit.DAY)
    CalendarViewMode.WEEK -> date.plus(7, DateTimeUnit.DAY)
    CalendarViewMode.MONTH -> {
        val nextMonthOrdinal = date.month.ordinal + 1
        if (nextMonthOrdinal == 12) {
            LocalDate(date.year + 1, Month.JANUARY, 1)
        } else {
            LocalDate(date.year, Month.entries[nextMonthOrdinal], 1)
        }
    }
}

/** Rewinds [date] by one step in the given [viewMode]. */
fun goPrevious(date: LocalDate, viewMode: CalendarViewMode): LocalDate = when (viewMode) {
    CalendarViewMode.DAY -> date.minus(1, DateTimeUnit.DAY)
    CalendarViewMode.FOUR_DAYS -> date.minus(4, DateTimeUnit.DAY)
    CalendarViewMode.WEEK -> date.minus(7, DateTimeUnit.DAY)
    CalendarViewMode.MONTH -> {
        val prevMonthOrdinal = date.month.ordinal - 1
        if (prevMonthOrdinal == -1) {
            LocalDate(date.year - 1, Month.DECEMBER, 1)
        } else {
            LocalDate(date.year, Month.entries[prevMonthOrdinal], 1)
        }
    }
}

/**
 * Returns a display label for the given [anchor] and [viewMode].
 * Examples: "September 2026", "Sep 16 – 19, 2026", "September 16, 2026".
 */
fun headerLabel(anchor: LocalDate, viewMode: CalendarViewMode): String {
    val range = visibleRange(anchor, viewMode)
    return when (viewMode) {
        CalendarViewMode.MONTH -> "${anchor.month.displayName()} ${anchor.year}"
        CalendarViewMode.DAY -> "${anchor.month.displayName()} ${anchor.dayOfMonth}, ${anchor.year}"
        else -> {
            val start = range.first()
            val end = range.last()
            if (start.month == end.month) {
                "${start.month.displayName()} ${start.dayOfMonth} – ${end.dayOfMonth}, ${end.year}"
            } else {
                "${start.month.displayName()} ${start.dayOfMonth} – ${end.month.displayName()} ${end.dayOfMonth}, ${end.year}"
            }
        }
    }
}

/** Returns the first day of the month containing [date]. */
fun firstDayOfMonth(date: LocalDate): LocalDate = LocalDate(date.year, date.month, 1)

/** Returns the last day of the month containing [date]. */
fun lastDayOfMonth(date: LocalDate): LocalDate {
    val nextMonthOrdinal = date.month.ordinal + 1
    val nextMonthYear = if (nextMonthOrdinal == 12) date.year + 1 else date.year
    val nextMonth = if (nextMonthOrdinal == 12) Month.JANUARY else Month.entries[nextMonthOrdinal]
    return LocalDate(nextMonthYear, nextMonth, 1).minus(1, DateTimeUnit.DAY)
}

/** English month name capitalised: "september" → "September". */
fun Month.displayName(): String = name.lowercase().replaceFirstChar { it.uppercase() }

/** Short ISO day-of-week name, Mon-anchored: Mon=0 → "Mon", Sun=6 → "Sun". */
fun LocalDate.dayOfWeekShort(): String {
    val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    return names.getOrElse(dayOfWeek.ordinal) { "" }
}
