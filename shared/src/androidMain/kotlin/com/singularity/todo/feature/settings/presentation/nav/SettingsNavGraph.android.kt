package com.singularity.todo.feature.settings.presentation.nav

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
import androidx.savedstate.serialization.SavedStateConfiguration
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.settings.SettingsScreen

/**
 * Android implementation of [SettingsNavGraph].
 * Creates a single-entry [NavDisplay] with [Settings] as the only entry,
 * providing [LocalSettingsNavigator] to all descendant screens.
 *
 * Uses [BackHandler] for system back gesture to exit the nested graph.
 * Uses [rememberViewModelStoreNavEntryDecorator] for per-entry VM scoping.
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
        BackHandler(enabled = backStack.size <= 1) { onExitGraph(null) }

        NavDisplay(
            backStack = backStack,
            modifier = modifier,
            onBack = { navigator.back() },
            entryDecorators = listOf(rememberViewModelStoreNavEntryDecorator()),
            entryProvider = entryProvider {
                entry<Settings> { SettingsScreen() }
            },
        )
    }
}
