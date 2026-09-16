package com.singularity.todo.feature.calendar.domain.model

/**
 * View mode for the Calendar screen — corresponds to the "Day / 4 days / Week / Month"
 * toggle in the top-right corner of the design.
 */
enum class CalendarViewMode(val label: String) {
    DAY("Day"),
    FOUR_DAYS("4 days"),
    WEEK("Week"),
    MONTH("Month"),
}
