package com.singularity.todo.feature.search.presentation.nav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.Search
import com.singularity.todo.feature.nav.navSavedStateConfig
import com.singularity.todo.feature.nav.rememberNavBackStackTyped
import com.singularity.todo.feature.search.SearchScreen

/**
 * Android implementation of [SearchNavGraph].
 * Creates a single-entry [NavDisplay] with [Search] as the only entry,
 * providing [LocalSearchNavigator] to all descendant screens.
 *
 * Uses [BackHandler] for system back gesture to exit the nested graph.
 * Uses [rememberViewModelStoreNavEntryDecorator] for per-entry VM scoping.
 *
 * Persistence: uses [navSavedStateConfig()] so the back stack survives process death.
 *
 * @param backStack Ignored on Android. Android always creates its own stack via
 *                  [rememberNavBackStack] with [navSavedStateConfig] for process-death survival.
 */
@Composable
actual fun SearchNavGraph(
    navCallbacks: NavCallbacks,
    modifier: Modifier,
    @Suppress("UNUSED_PARAMETER") backStack: NavBackStack<Search>?,
) {
    val stack: NavBackStack<Search> = rememberNavBackStackTyped(navSavedStateConfig(), Search)

    val onExitGraph: (AppDestination?) -> Unit = navCallbacks.graphExit

    val navigator = remember(onExitGraph) {
        SearchNavigator(onExitGraph)
    }

    CompositionLocalProvider(
        LocalSearchNavigator provides navigator,
    ) {
        BackHandler(enabled = stack.size <= 1) { onExitGraph(null) }

        NavDisplay(
            backStack = stack,
            modifier = modifier,
            onBack = { navigator.back() },
            entryDecorators = listOf(rememberViewModelStoreNavEntryDecorator()),
            entryProvider = entryProvider {
                entry<Search> { SearchScreen() }
            },
        )
    }
}
