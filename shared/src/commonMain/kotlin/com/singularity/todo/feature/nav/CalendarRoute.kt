package com.singularity.todo.feature.nav

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * Navigation routes inside the Calendar nested graph.
 *
 * Serialization is required because [androidx.navigation3.runtime.rememberNavBackStack]
 * encodes the stack on each remember via Compose's SaveableStateHolder.
 */
@Serializable
sealed interface CalendarRoute : AppNavKey {

    /**
     * Month overview — the default start route.
     * [anchor] is the 1st day of the month to display (used to derive year/month).
     */
    @Serializable
    data class Month(val anchor: String) : CalendarRoute {
        val date: LocalDate get() = LocalDate.parse(anchor)
    }

    /**
     * Day focus — a specific day highlighted (used when navigating from a click
     * on a task to the day it belongs to).
     */
    @Serializable
    data class Day(val anchor: String) : CalendarRoute {
        val date: LocalDate get() = LocalDate.parse(anchor)
    }
}
