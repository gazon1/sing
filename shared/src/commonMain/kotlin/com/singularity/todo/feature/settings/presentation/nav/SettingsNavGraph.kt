package com.singularity.todo.feature.settings.presentation.nav

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
import com.singularity.todo.feature.nav.Settings
import com.singularity.todo.feature.nav.SystemBackHandler
import com.singularity.todo.feature.nav.rememberAppNavBackStack
import com.singularity.todo.feature.settings.SettingsScreen

/**
 * The nested navigation graph of the settings feature.
 *
 * Provides its own [NavBackStack] with [Settings] as the sole entry. Tabs are local
 * `var selectedTab by remember` state inside [SettingsContent] — no navigation
 * required for tab switching.
 *
 * @param navCallbacks the outer [NavCallbacks] for cross-graph navigation. Used to
 *   build the [onExitGraph][SettingsNavigator.onExitGraph] callback.
 * @param modifier compose modifier for the inner [NavDisplay].
 * @param backStack optional pre-created stack. Desktop passes a stack hoisted into
 *   the shell's composition, so the nested state survives a tab switch.
 */
@Composable
fun SettingsNavGraph(
    navCallbacks: NavCallbacks,
    modifier: Modifier = Modifier,
    backStack: NavBackStack<Settings>? = null,
) {
    val stack: NavBackStack<Settings> = backStack ?: rememberAppNavBackStack(Settings)
    val onExitGraph: (AppDestination?) -> Unit = navCallbacks.graphExit
    val navigator = remember(onExitGraph) {
        SettingsNavigator(onExitGraph)
    }
    CompositionLocalProvider(
        LocalSettingsNavigator provides navigator,
    ) {
        // Intercept the system back at the start route to exit the nested graph.
        SystemBackHandler(enabled = stack.size <= 1) { onExitGraph(null) }

        NavDisplay(
            backStack = stack,
            modifier = modifier,
            onBack = { navigator.back() },
            entryDecorators = rememberAppEntryDecorators(),
            entryProvider = settingsScreenEntryProvider(),
        )
    }
}

/**
 * Builds the [entryProvider][androidx.navigation3.runtime.entryProvider] for the Settings
 * nested graph — the map from [Settings] to [SettingsScreen].
 */
@Composable
internal fun settingsScreenEntryProvider() = entryProvider {
    entry<Settings> { SettingsScreen() }
}
