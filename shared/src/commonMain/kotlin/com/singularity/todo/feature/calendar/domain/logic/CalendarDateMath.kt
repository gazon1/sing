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

        CalendarViewMode.DAY -> "${anchor.month.displayName()} ${anchor.day}, ${anchor.year}"

        else -> {
            val start = range.first()
            val end = range.last()
            if (start.month == end.month) {
                "${start.month.displayName()} ${start.day} – ${end.day}, ${end.year}"
            } else {
                "${start.month.displayName()} ${start.day} – ${end.month.displayName()} ${end.day}, ${end.year}"
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

/**
 * Represents a year+month pair without a specific day. Used as the page identity
 * for [androidx.compose.foundation.pager.HorizontalPager] in month-view.
 */
data class YearMonth(val year: Int, val month: Month) {
    init {
        require(year in YEAR_MIN..YEAR_MAX) { "year=$year out of range [$YEAR_MIN, $YEAR_MAX]" }
    }

    companion object {
        const val YEAR_MIN: Int = 1900
        const val YEAR_MAX: Int = 2200
        const val MONTHS_PER_YEAR: Int = 12
    }
}

/** Converts a [LocalDate] to a [YearMonth] (1st-day-normalised). */
fun LocalDate.toYearMonth(): YearMonth = YearMonth(year, month)

/** Converts a [YearMonth] to the 1st-day [LocalDate] of that month. */
fun YearMonth.toLocalDate(): LocalDate = LocalDate(year, month, 1)

/**
 * Returns the inclusive (min, max) [YearMonth] range supported by the swipeable month
 * pager. Range is `[anchor - span months, anchor + span months]`.
 *
 * Default [span] = 120 ⇒ ±120 months (±10 years) of swipeable history.
 */
fun monthPageRange(anchor: YearMonth, span: Int = 120): Pair<YearMonth, YearMonth> {
    val totalMin = anchor.year * YearMonth.MONTHS_PER_YEAR + (anchor.month.ordinal - span)
    val totalMax = anchor.year * YearMonth.MONTHS_PER_YEAR + (anchor.month.ordinal + span)
    return monthIndexToYearMonth(totalMin) to monthIndexToYearMonth(totalMax)
}

/**
 * Maps a [page] (0..[pageCount]-1) of the swipeable month pager to its [YearMonth].
 *
 * Pager pages are offset from the anchor's middle position ([span]) so that
 * the initial month lands near index [span] for forward/backward swipe headroom.
 */
fun yearMonthForPage(anchor: YearMonth, page: Int, span: Int = 120): YearMonth {
    val totalMonths = anchor.year * YearMonth.MONTHS_PER_YEAR +
        anchor.month.ordinal + (page - span)
    return monthIndexToYearMonth(totalMonths)
}

/**
 * Inverse of [yearMonthForPage]: returns the pager page index for a [YearMonth].
 * Returns null if [target] is outside the [anchor ± span] window — caller must
 * either expand the range or refuse the jump.
 */
fun pageForYearMonth(anchor: YearMonth, target: YearMonth, span: Int = 120): Int? {
    val anchorTotal = anchor.year * YearMonth.MONTHS_PER_YEAR + anchor.month.ordinal
    val targetTotal = target.year * YearMonth.MONTHS_PER_YEAR + target.month.ordinal
    val delta = targetTotal - anchorTotal
    return if (delta in -span..span) delta + span else null
}

/** Converts an absolute month index (months since year 0) to a [YearMonth]. */
private fun monthIndexToYearMonth(totalMonths: Int): YearMonth {
    val year = totalMonths / YearMonth.MONTHS_PER_YEAR
    val monthOrdinal = totalMonths - year * YearMonth.MONTHS_PER_YEAR
    val month = Month.entries[monthOrdinal]
    return YearMonth(year, month)
}
