package com.singularity.todo.feature.calendar.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.feature.calendar.presentation.screen.CalendarScreen
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.CalendarRoute
import com.singularity.todo.feature.nav.rememberInMemoryNavBackStack

/**
 * JVM Desktop implementation of [CalendarNavGraph].
 *
 * Uses an in-memory [NavBackStack] — no process death on Desktop, so
 * SavedStateConfiguration is not needed.
 * No system back gesture on desktop — handled via the outer app's toolbar.
 * No [rememberViewModelStoreNavEntryDecorator] needed on JVM desktop.
 */
@Composable
actual fun CalendarNavGraph(start: CalendarRoute, onExitGraph: (AppDestination?) -> Unit, modifier: Modifier) {
    val backStack: NavBackStack<CalendarRoute> = rememberInMemoryNavBackStack(start)

    val navigator = remember(backStack, onExitGraph) {
        CalendarNavigator(backStack, onExitGraph)
    }

    CompositionLocalProvider(
        LocalCalendarNavigator provides navigator,
    ) {
        NavDisplay(
            backStack = backStack,
            modifier = modifier,
            onBack = { navigator.back() },
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
