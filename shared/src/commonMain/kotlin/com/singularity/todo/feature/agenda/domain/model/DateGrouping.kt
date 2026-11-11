package com.singularity.todo.feature.agenda.domain.model

/**
 * How a date-grouped section is split.
 *
 * The periods are derived from the tasks that are actually there: a week is the
 * Monday–Sunday week a due date belongs to, a month is its calendar month. There
 * is no list of thirty days, twelve weeks or twelve months — a task due in three
 * years still has a period, and a period with no task does not become a header.
 *
 * The choice is stored per profile. A section declares the grouping it means
 * ([Section.groupBy]); the screen may override it with the profile's choice.
 */
enum class DateGrouping {
    Day,
    Week,
    Month,
    ;

    companion object {
        /** The grouping a new profile starts with. */
        val Default: DateGrouping = Day
    }
}
