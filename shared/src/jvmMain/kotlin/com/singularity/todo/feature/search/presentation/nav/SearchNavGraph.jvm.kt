package com.singularity.todo.feature.search.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.search.SearchScreen

/**
 * JVM Desktop implementation of [SearchNavGraph].
 *
 * On desktop there is no system back gesture — [BackHandler][androidx.activity.compose.BackHandler]
 * is a no-op. Back navigation is handled via the outer app's toolbar / window controls.
 */
@Composable
actual fun SearchNavGraph(
    navCallbacks: NavCallbacks,
    modifier: Modifier,
) {
    val savedStateConfig = remember { SavedStateConfiguration { } }
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
        // Desktop has no system back gesture — BackHandler is a no-op on JVM.

        NavDisplay(
            backStack = backStack,
            modifier = modifier,
            onBack = { navigator.back() },
            // No entryDecorators on JVM desktop.
            entryProvider = entryProvider {
                entry<Search> { SearchScreen() }
            },
        )
    }
}
