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
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.feature.calendar.presentation.screen.CalendarScreen
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.CalendarRoute
import com.singularity.todo.feature.nav.appNavSavedStateConfig

/**
 * Android implementation of [CalendarNavGraph].
 * Creates a nested [NavDisplay] with its own [NavBackStack] for the Calendar feature,
 * providing [LocalCalendarNavigator] to all descendant screens.
 *
 * Uses [BackHandler] for system back gesture at the start route.
 * Uses [rememberViewModelStoreNavEntryDecorator] to fix the Koin scoping bug where
 * LocalViewModelStoreOwner resolves to ComponentActivity instead of the NavEntry.
 *
 * Persistence: uses [appNavSavedStateConfig] so the back stack survives process death.
 */
@Composable
actual fun CalendarNavGraph(start: CalendarRoute, onExitGraph: (AppDestination?) -> Unit, modifier: Modifier) {
    val savedStateConfig = appNavSavedStateConfig

    @Suppress("UNCHECKED_CAST")
    val backStack: NavBackStack<CalendarRoute> = rememberNavBackStack(savedStateConfig, start)
        as NavBackStack<CalendarRoute>

    val navigator = remember(backStack, onExitGraph) {
        CalendarNavigator(backStack, onExitGraph)
    }

    CompositionLocalProvider(
        LocalCalendarNavigator provides navigator,
    ) {
        BackHandler(enabled = backStack.size <= 1) { onExitGraph(null) }

        NavDisplay(
            backStack = backStack,
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
