package com.singularity.todo.feature.agenda.domain.logic

import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Converts a [RelativeBucket] to an inclusive date range using the given `today`.
 */
fun RelativeBucket.toDateRange(today: LocalDate): DateRange = when (this) {
    RelativeBucket.Today -> DateRange(today, today)

    RelativeBucket.Yesterday -> DateRange(today.minus(1, DateTimeUnit.DAY), today.minus(1, DateTimeUnit.DAY))

    RelativeBucket.Tomorrow -> DateRange(today.plus(1, DateTimeUnit.DAY), today.plus(1, DateTimeUnit.DAY))

    RelativeBucket.ThisWeek -> {
        val dow = today.dayOfWeek.ordinal // 0 = Monday
        val startOfWeek = today.minus(dow, DateTimeUnit.DAY)
        val endOfWeek = startOfWeek.plus(6, DateTimeUnit.DAY)
        DateRange(startOfWeek, endOfWeek)
    }

    RelativeBucket.NextWeek -> {
        val dow = today.dayOfWeek.ordinal
        val startOfThisWeek = today.minus(dow, DateTimeUnit.DAY)
        val startOfNextWeek = startOfThisWeek.plus(7, DateTimeUnit.DAY)
        DateRange(startOfNextWeek, startOfNextWeek.plus(6, DateTimeUnit.DAY))
    }

    RelativeBucket.ThisMonth -> DateRange(
        LocalDate(today.year, today.month, 1),
        today.endOfMonth(),
    )

    RelativeBucket.NextMonth -> {
        val nextMonth = today.plus(1, DateTimeUnit.MONTH)
        DateRange(
            LocalDate(nextMonth.year, nextMonth.month, 1),
            nextMonth.endOfMonth(),
        )
    }

    RelativeBucket.Overdue -> DateRange(
        LocalDate(1970, 1, 1),
        today.minus(1, DateTimeUnit.DAY),
    )

    RelativeBucket.NoDate -> DateRange(
        LocalDate(1970, 1, 1),
        LocalDate(1970, 1, 1),
    )
}

private fun LocalDate.endOfMonth(): LocalDate {
    val nextMonth = this.plus(1, DateTimeUnit.MONTH)
    return LocalDate(nextMonth.year, nextMonth.month, 1).minus(1, DateTimeUnit.DAY)
}

/** Inclusive date range. */
data class DateRange(val from: LocalDate, val to: LocalDate)

// The `inline fun todayInSystemZone()` that used to be re-exported here is gone
// (2026-10-05, #91). It was a convenience wrapper around the system clock, and
// two spellings of "today, unreadable" is one more than a domain logic file
// should have to choose between. Callers now take the date they are given: a
// bucket is computed from a date, not from the wall clock.
