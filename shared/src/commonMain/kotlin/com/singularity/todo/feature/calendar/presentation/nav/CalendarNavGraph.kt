package com.singularity.todo.feature.calendar.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.feature.calendar.presentation.screen.CalendarScreen
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.rememberAppEntryDecorators
import com.singularity.todo.feature.nav.CalendarRoute
import com.singularity.todo.feature.nav.SystemBackHandler
import com.singularity.todo.feature.nav.rememberAppNavBackStack

/**
 * The nested navigation graph of the Calendar feature.
 *
 * Provides its own [NavBackStack] with [CalendarRoute] keys, independent of the
 * outer app back stack. Screens inside use [LocalCalendarNavigator] to navigate
 * without manual callbacks.
 *
 * @param start the initial [CalendarRoute] to show.
 * @param onExitGraph called when the nested graph should close. The [AppDestination]
 *   argument, if non-null, is the destination to navigate to in the outer graph.
 * @param modifier compose modifier for the inner [NavDisplay].
 * @param backStack optional pre-created stack. Desktop passes a stack hoisted into
 *   the shell's composition, so the nested state survives a tab switch.
 */
@Composable
fun CalendarNavGraph(
    start: CalendarRoute,
    onExitGraph: (AppDestination?) -> Unit,
    modifier: Modifier = Modifier,
    backStack: NavBackStack<CalendarRoute>? = null,
) {
    val stack: NavBackStack<CalendarRoute> = backStack ?: rememberAppNavBackStack(start)
    val navigator = remember(stack, onExitGraph) {
        CalendarNavigator(stack, onExitGraph)
    }
    CompositionLocalProvider(
        LocalCalendarNavigator provides navigator,
    ) {
        // Intercept the system back at the start route to exit the nested graph.
        SystemBackHandler(enabled = stack.size <= 1) { onExitGraph(null) }

        NavDisplay(
            backStack = stack,
            modifier = modifier,
            onBack = { navigator.back() },
            entryDecorators = rememberAppEntryDecorators(),
            entryProvider = calendarScreenEntryProvider(),
        )
    }
}

/**
 * Builds the [entryProvider][androidx.navigation3.runtime.entryProvider] for the Calendar
 * nested graph — the map from each [CalendarRoute] variant to the screen that renders it.
 */
@Composable
internal fun calendarScreenEntryProvider() = entryProvider {
    entry<CalendarRoute.Month> { route -> CalendarScreen(route.date) }
    entry<CalendarRoute.Day> { route -> CalendarScreen(route.date) }
}
