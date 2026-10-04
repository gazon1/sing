package com.singularity.todo.feature.search.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.Search
import com.singularity.todo.feature.nav.rememberInMemoryNavBackStack
import com.singularity.todo.feature.search.SearchScreen

/**
 * JVM Desktop implementation of [SearchNavGraph].
 *
 * Uses an in-memory [NavBackStack] — no process death on Desktop, so
 * [SavedStateConfiguration] is dead code and was removed.
 *
 * On desktop there is no system back gesture — [BackHandler][androidx.activity.compose.BackHandler]
 * is a no-op. Back navigation is handled via the outer app's toolbar / window controls.
 *
 * No [rememberViewModelStoreNavEntryDecorator][androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator]
 * is used on JVM desktop.
 *
 * @param backStack Pre-created stack. When non-null the stack is NOT re-created
 *   on recomposition, which prevents nested navigation state from being lost when
 *   the parent [com.singularity.todo.feature.nav.Nav3State] triggers recomposition.
 *   When null (default), creates a new stack via [rememberInMemoryNavBackStack].
 */
@Composable
actual fun SearchNavGraph(navCallbacks: NavCallbacks, modifier: Modifier, backStack: NavBackStack<Search>?) {
    val stack: NavBackStack<Search> = backStack ?: rememberInMemoryNavBackStack(Search)

    val onExitGraph: (AppDestination?) -> Unit = navCallbacks.graphExit

    val navigator = remember(onExitGraph) {
        SearchNavigator(onExitGraph)
    }

    CompositionLocalProvider(
        LocalSearchNavigator provides navigator,
    ) {
        // Desktop has no system back gesture — BackHandler is a no-op on JVM.

        NavDisplay(
            backStack = stack,
            modifier = modifier,
            onBack = { navigator.back() },
            entryProvider = entryProvider {
                entry<Search> { SearchScreen() }
            },
        )
    }
}
