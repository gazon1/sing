package com.singularity.todo.feature.settings.presentation.nav

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
import com.singularity.todo.feature.settings.SettingsScreen

/**
 * JVM Desktop implementation of [SettingsNavGraph].
 *
 * On desktop there is no system back gesture — [BackHandler][androidx.activity.compose.BackHandler]
 * is a no-op. Back navigation is handled via the outer app's toolbar / window controls.
 *
 * No [rememberViewModelStoreNavEntryDecorator][androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator]
 * is used on JVM desktop — the JVM does not have the ComponentActivity-based ViewModelStore
 * scoping issue that Android has.
 */
@Composable
actual fun SettingsNavGraph(
    navCallbacks: NavCallbacks,
    modifier: Modifier,
) {
    val savedStateConfig = remember { SavedStateConfiguration { } }
    @Suppress("UNCHECKED_CAST")
    val backStack: NavBackStack<Settings> = rememberNavBackStack(savedStateConfig, Settings)
        as NavBackStack<Settings>

    val onExitGraph: (AppDestination?) -> Unit = { dest ->
        if (dest != null) {
            navCallbacks.navigate(dest)
        } else {
            navCallbacks.goBack()
        }
    }

    val navigator = remember(onExitGraph) {
        SettingsNavigator(onExitGraph)
    }

    CompositionLocalProvider(
        LocalSettingsNavigator provides navigator,
    ) {
        // Desktop has no system back gesture — BackHandler is a no-op on JVM.

        NavDisplay(
            backStack = backStack,
            modifier = modifier,
            onBack = { navigator.back() },
            // No entryDecorators on JVM desktop.
            entryProvider = entryProvider {
                entry<Settings> { SettingsScreen() }
            },
        )
    }
}
