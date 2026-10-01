package com.singularity.todo.feature.search.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.Search

/**
 * Creates a nested navigation graph for the search feature.
 *
 * Provides its own [androidx.navigation3.runtime.NavBackStack] with [Search] as the sole
 * entry. The search form is the only screen inside this graph.
 *
 * ## Architecture
 *
 * - [LocalSearchNavigator], [SearchNavigator], [Search] — commonMain (platform-agnostic)
 * - [SearchNavGraph] — expect/actual composable
 *   - Android: includes [androidx.activity.compose.BackHandler] for system back gesture
 *   - JVM: no back handler (desktop has no system back gesture)
 *
 * @param navCallbacks The outer [NavCallbacks] for cross-graph navigation.
 *                     Used to build the [onExitGraph][SearchNavigator.onExitGraph] callback.
 * @param modifier Compose modifier for the inner [NavDisplay][androidx.navigation3.ui.NavDisplay].
 * @param backStack Optional pre-created stack. When provided, the graph uses this stack
 *                  instead of creating a new one. Used by JVM Desktop to pass a stable
 *                  stack created via [rememberInMemoryNavBackStack] in the entry block.
 */
@Composable
expect fun SearchNavGraph(
    navCallbacks: NavCallbacks,
    modifier: Modifier = Modifier,
    backStack: NavBackStack<Search>? = null,
)
