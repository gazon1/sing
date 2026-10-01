package com.singularity.todo.feature.settings.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.Settings

/**
 * Creates a nested navigation graph for the settings feature.
 *
 * Provides its own [androidx.navigation3.runtime.NavBackStack] with [Settings] as the sole
 * entry. Tabs are local `var selectedTab by remember` state inside [SettingsContent]
 * — no navigation required for tab switching.
 *
 * ## Architecture
 *
 * - [LocalSettingsNavigator], [SettingsNavigator], [Settings] — commonMain (platform-agnostic)
 * - [SettingsNavGraph] — expect/actual composable
 *   - Android: includes [androidx.activity.compose.BackHandler] for system back gesture
 *   - JVM: no back handler (desktop has no system back gesture)
 *
 * @param navCallbacks The outer [NavCallbacks] for cross-graph navigation.
 *                     Used to build the [onExitGraph][SettingsNavigator.onExitGraph] callback.
 * @param modifier Compose modifier for the inner [NavDisplay][androidx.navigation3.ui.NavDisplay].
 * @param backStack Optional pre-created stack. When provided, the graph uses this stack
 *                  instead of creating a new one. Used by JVM Desktop to pass a stable
 *                  stack created via [rememberInMemoryNavBackStack] in the entry block.
 */
@Composable
expect fun SettingsNavGraph(
    navCallbacks: NavCallbacks,
    modifier: Modifier = Modifier,
    backStack: NavBackStack<Settings>? = null,
)
