package com.singularity.todo.core.ui.components.sheet

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
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
