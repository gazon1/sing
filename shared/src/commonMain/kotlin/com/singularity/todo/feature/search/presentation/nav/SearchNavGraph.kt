package com.singularity.todo.feature.search.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.rememberAppEntryDecorators
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.Search
import com.singularity.todo.feature.nav.SystemBackHandler
import com.singularity.todo.feature.nav.rememberAppNavBackStack
import com.singularity.todo.feature.search.SearchScreen

/**
 * The nested navigation graph of the search feature.
 *
 * Provides its own [NavBackStack] with [Search] as the sole entry. The search form
 * is the only screen inside this graph.
 *
 * @param navCallbacks the outer [NavCallbacks] for cross-graph navigation. Used to
 *   build the [onExitGraph][SearchNavigator.onExitGraph] callback.
 * @param modifier compose modifier for the inner [NavDisplay].
 * @param backStack optional pre-created stack. Desktop passes a stack hoisted into
 *   the shell's composition, so the nested state survives a tab switch.
 */
@Composable
fun SearchNavGraph(
    navCallbacks: NavCallbacks,
    modifier: Modifier = Modifier,
    backStack: NavBackStack<Search>? = null,
) {
    val stack: NavBackStack<Search> = backStack ?: rememberAppNavBackStack(Search)
    val onExitGraph: (AppDestination?) -> Unit = navCallbacks.graphExit
    val navigator = remember(onExitGraph) {
        SearchNavigator(onExitGraph)
    }
    CompositionLocalProvider(
        LocalSearchNavigator provides navigator,
    ) {
        // Intercept the system back at the start route to exit the nested graph.
        SystemBackHandler(enabled = stack.size <= 1) { onExitGraph(null) }

        NavDisplay(
            backStack = stack,
            modifier = modifier,
            onBack = { navigator.back() },
            entryDecorators = rememberAppEntryDecorators(),
            entryProvider = searchScreenEntryProvider(),
        )
    }
}

/**
 * Builds the [entryProvider][androidx.navigation3.runtime.entryProvider] for the Search
 * nested graph — the map from [Search] to [SearchScreen].
 */
@Composable
internal fun searchScreenEntryProvider() = entryProvider {
    entry<Search> { SearchScreen() }
}
