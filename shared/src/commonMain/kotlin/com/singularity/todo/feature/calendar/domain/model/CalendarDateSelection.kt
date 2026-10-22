package com.singularity.todo.feature.calendar.domain.model

import kotlinx.datetime.LocalDate

/**
 * The days the user has picked for one task, and whether the picking is on.
 *
 * ## Why a value and not a flag plus a set
 *
 * The mode and the days belong together: the mode is on exactly while there is
 * something to pick, and the days are meaningless outside it. A pair of
 * independent fields can reach the four combinations, three of which say nothing.
 *
 * ## Why it is a value
 *
 * It is a value with no clock and no dispatcher, so the rule — a tap picks a
 * day, a second tap on the same day unpicks it — can be asserted on its own.
 * The ViewModel holds it; the screen projects it.
 */
data class CalendarDateSelection(
    /** Whether the calendar is picking days instead of opening them. */
    val isActive: Boolean = false,
    /** The picked days. Empty when the mode is off. */
    val dates: Set<LocalDate> = emptySet(),
) {
    /** Enters the mode with [date] already picked. */
    fun enter(date: LocalDate): CalendarDateSelection =
        copy(isActive = true, dates = setOf(date))

    /** Picks [date], or unpicks it when it is already picked. */
    fun toggle(date: LocalDate): CalendarDateSelection =
        copy(dates = if (date in dates) dates - date else dates + date)

    /** Leaves the mode and forgets the picked days. */
    fun exit(): CalendarDateSelection = CalendarDateSelection()
}
