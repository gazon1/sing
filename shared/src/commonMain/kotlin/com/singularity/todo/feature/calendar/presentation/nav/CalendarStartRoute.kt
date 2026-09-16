package com.singularity.todo.feature.calendar.presentation.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Start routes for the Calendar nested graph.
 * Used by [AppDestination.CalendarGraph] to parameterise the start of the calendar.
 */
@Serializable
sealed interface CalendarStartRoute : NavKey {

    /** Start at the current month (anchor = today). */
    @Serializable
    data class Month(val anchor: String) : CalendarStartRoute
}
