package com.singularity.todo.core.ui.components

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month

/**
 * Formats a date as a short Russian label: "Сб, 05 сент 2026".
 * Returns `null` when [date] is `null`.
 */
internal fun formatRussianDueDate(date: LocalDate?): String? {
    if (date == null) return null
    return "${
        shortWeekday(
            date.dayOfWeek,
        )
    }, ${
        date.day.toString()
            .padStart(2, '0')
    } ${shortMonth(date.month)} ${date.year}"
}

/**
 * The date a task editor row shows: the same label the agenda list shows, with
 * the time appended when there is one.
 *
 * The editor used to print the raw ISO date ("2026-10-30") while the list showed
 * "Пт, 30 окт 2026" — one date, two shapes, on the same phone.
 */
internal fun formatRussianDueDateWithTime(
    date: LocalDate,
    time: LocalTime?,
): String = formatRussianDueDate(date) + (time?.let { " ${it.toString().take(5)}" } ?: "")

/**
 * Says a number of dates the way a person says it: "1 date", "2 dates".
 */
internal fun formatDateCount(count: Int): String {
    val word = if (count == 1) "date" else "dates"
    return "$count $word"
}

private fun shortWeekday(d: DayOfWeek): String =
    when (d) {
        DayOfWeek.MONDAY -> "Пн"
        DayOfWeek.TUESDAY -> "Вт"
        DayOfWeek.WEDNESDAY -> "Ср"
        DayOfWeek.THURSDAY -> "Чт"
        DayOfWeek.FRIDAY -> "Пт"
        DayOfWeek.SATURDAY -> "Сб"
        DayOfWeek.SUNDAY -> "Вс"
    }

private fun shortMonth(m: Month): String =
    when (m) {
        Month.JANUARY -> "янв"
        Month.FEBRUARY -> "фев"
        Month.MARCH -> "мар"
        Month.APRIL -> "апр"
        Month.MAY -> "мая"
        Month.JUNE -> "июн"
        Month.JULY -> "июл"
        Month.AUGUST -> "авг"
        Month.SEPTEMBER -> "сен"
        Month.OCTOBER -> "окт"
        Month.NOVEMBER -> "ноя"
        Month.DECEMBER -> "дек"
    }

/**
 * Formats a month title: "Октябрь 2026".
 *
 * The full month name, not the short one: a title is the only place in the picker
 * where the month is named, and "окт 2026" next to a grid of days is a second
 * thing to decode.
 */
internal fun formatRussianMonthYear(date: LocalDate): String = "${longMonth(date.month)} ${date.year}"

/** The weekday headers of a Monday-anchored grid: "Пн", "Вт", …, "Вс". */
internal val russianWeekdayHeaders: List<String> = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

/**
 * Formats a date the way a list of dates reads well: "05 сент".
 *
 * Shorter than [formatRussianDueDate], which carries the weekday and the year —
 * right for one date on its own row, too long when several are on one row.
 */
internal fun formatRussianShortDate(date: LocalDate): String =
    "${date.day.toString().padStart(2, '0')} ${shortMonth(date.month)}"

/**
 * Formats several dates for a row: "12 окт, 17 окт, 3 ноя".
 *
 * A pure function on purpose: the row renders it, and a test asserts it without
 * a composable in the way.
 */
internal fun formatRussianDateList(dates: List<LocalDate>): String =
    dates.joinToString(", ") { formatRussianShortDate(it) }

private fun longMonth(m: Month): String =
    when (m) {
        Month.JANUARY -> "Январь"
        Month.FEBRUARY -> "Февраль"
        Month.MARCH -> "Март"
        Month.APRIL -> "Апрель"
        Month.MAY -> "Май"
        Month.JUNE -> "Июнь"
        Month.JULY -> "Июль"
        Month.AUGUST -> "Август"
        Month.SEPTEMBER -> "Сентябрь"
        Month.OCTOBER -> "Октябрь"
        Month.NOVEMBER -> "Ноябрь"
        Month.DECEMBER -> "Декабрь"
    }
