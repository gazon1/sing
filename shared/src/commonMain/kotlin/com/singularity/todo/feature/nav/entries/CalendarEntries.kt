package com.singularity.todo.feature.nav.entries

import androidx.navigation3.runtime.EntryProviderScope
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.AppNavStacks
import com.singularity.todo.feature.nav.CalendarRoute
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.stableContentKey
import com.singularity.todo.feature.nav.toCalendarRoute
import com.singularity.todo.feature.calendar.presentation.nav.CalendarNavGraph
import kotlinx.datetime.LocalDate

/**
 * The app-level entries the calendar feature owns.
 *
 * @param nav the outer navigation callbacks.
 * @param stacks the stacks the platform hoists.
 * @param today the date the calendar opens on. Passed in rather than read from
 *   the clock inside the graph: a date computed outside the Koin graph is a date
 *   a test cannot reach (#91).
 */
fun EntryProviderScope<AppDestination>.calendarEntries(
    nav: NavCallbacks,
    stacks: AppNavStacks,
    today: LocalDate,
) {
    entry<AppDestination.Calendar>(clazzContentKey = { it.stableContentKey() }) {
        CalendarNavGraph(
            start = CalendarRoute.Month(today.toString()),
            onExitGraph = nav.graphExit,
            backStack = stacks.calendar,
        )
    }

    entry<AppDestination.CalendarGraph>(clazzContentKey = { it.stableContentKey() }) { route ->
        CalendarNavGraph(
            start = route.start.toCalendarRoute(),
            onExitGraph = nav.graphExit,
        )
    }
}
