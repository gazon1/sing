package com.singularity.todo.core.ui.components

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month

/**
 * Formats a due date as a short Russian label: "Сб, 05 сент 2026".
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

private fun shortWeekday(d: DayOfWeek): String = when (d) {
    DayOfWeek.MONDAY -> "Пн"
    DayOfWeek.TUESDAY -> "Вт"
    DayOfWeek.WEDNESDAY -> "Ср"
    DayOfWeek.THURSDAY -> "Чт"
    DayOfWeek.FRIDAY -> "Пт"
    DayOfWeek.SATURDAY -> "Сб"
    DayOfWeek.SUNDAY -> "Вс"
}

private fun shortMonth(m: Month): String = when (m) {
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
