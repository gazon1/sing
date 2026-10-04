package com.singularity.todo.feature.calendar.presentation.nav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.feature.calendar.presentation.screen.CalendarScreen
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.CalendarRoute
import com.singularity.todo.feature.nav.navSavedStateConfig
import com.singularity.todo.feature.nav.rememberNavBackStackTyped

/**
 * Android implementation of [CalendarNavGraph].
 * Creates a nested [NavDisplay] with its own [NavBackStack] for the Calendar feature,
 * providing [LocalCalendarNavigator] to all descendant screens.
 *
 * Uses [BackHandler] for system back gesture at the start route.
 * Uses [rememberViewModelStoreNavEntryDecorator] to fix the Koin scoping bug where
 * LocalViewModelStoreOwner resolves to ComponentActivity instead of the NavEntry.
 *
 * Persistence: uses [navSavedStateConfig()] so the back stack survives process death.
 *
 * @param backStack Ignored on Android. Android always creates its own stack via
 *                  [rememberNavBackStack] with [navSavedStateConfig] for process-death survival.
 */
@Composable
actual fun CalendarNavGraph(
    start: CalendarRoute,
    onExitGraph: (AppDestination?) -> Unit,
    modifier: Modifier,
    @Suppress("UNUSED_PARAMETER") backStack: NavBackStack<CalendarRoute>?,
) {
    val stack: NavBackStack<CalendarRoute> = rememberNavBackStackTyped(navSavedStateConfig(), start)

    val navigator = remember(stack, onExitGraph) {
        CalendarNavigator(stack, onExitGraph)
    }

    CompositionLocalProvider(
        LocalCalendarNavigator provides navigator,
    ) {
        BackHandler(enabled = stack.size <= 1) { onExitGraph(null) }

        NavDisplay(
            backStack = stack,
            modifier = modifier,
            onBack = { navigator.back() },
            entryDecorators = listOf(rememberViewModelStoreNavEntryDecorator()),
            entryProvider = entryProvider {
                entry<CalendarRoute.Month> { route -> CalendarScreen(route.date) }
                entry<CalendarRoute.Day> { route -> CalendarScreen(route.date) }
            },
        )
    }
}

@Composable
actual fun calendarEntryProvider(): (CalendarRoute) -> NavEntry<CalendarRoute> = entryProvider {
    entry<CalendarRoute.Month> { route -> CalendarScreen(route.date) }
    entry<CalendarRoute.Day> { route -> CalendarScreen(route.date) }
}
