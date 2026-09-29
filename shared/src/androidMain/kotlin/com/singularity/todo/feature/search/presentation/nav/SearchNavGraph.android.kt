package com.singularity.todo.feature.search.presentation.nav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.Search
import com.singularity.todo.feature.nav.navSavedStateConfig
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
 */
@Composable
actual fun SearchNavGraph(navCallbacks: NavCallbacks, modifier: Modifier) {
    // remember { }, not rememberSaveable { }. SavedStateConfiguration is a schema
    // (which concrete NavKey subtypes exist), not a value to persist.
    val savedStateConfig = navSavedStateConfig()

    @Suppress("UNCHECKED_CAST")
    val backStack: NavBackStack<Search> = rememberNavBackStack(savedStateConfig, Search)
        as NavBackStack<Search>

    val onExitGraph: (AppDestination?) -> Unit = { dest ->
        if (dest != null) {
            navCallbacks.navigate(dest)
        } else {
            navCallbacks.goBack()
        }
    }

    val navigator = remember(onExitGraph) {
        SearchNavigator(onExitGraph)
    }

    CompositionLocalProvider(
        LocalSearchNavigator provides navigator,
    ) {
        BackHandler(enabled = backStack.size <= 1) { onExitGraph(null) }

        NavDisplay(
            backStack = backStack,
            modifier = modifier,
            onBack = { navigator.back() },
            entryDecorators = listOf(rememberViewModelStoreNavEntryDecorator()),
            entryProvider = entryProvider {
                entry<Search> { SearchScreen() }
            },
        )
    }
}
