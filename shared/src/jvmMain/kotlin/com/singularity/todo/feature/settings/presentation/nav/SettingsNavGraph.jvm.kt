package com.singularity.todo.feature.settings.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.Settings
import com.singularity.todo.feature.nav.rememberInMemoryNavBackStack
import com.singularity.todo.feature.settings.SettingsScreen

/**
 * JVM Desktop implementation of [SettingsNavGraph].
 *
 * Uses an in-memory [NavBackStack] — no process death on Desktop, so
 * [SavedStateConfiguration] is dead code and was removed.
 *
 * On desktop there is no system back gesture — [BackHandler][androidx.activity.compose.BackHandler]
 * is a no-op. Back navigation is handled via the outer app's toolbar / window controls.
 *
 * No [rememberViewModelStoreNavEntryDecorator][androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator]
 * is used on JVM desktop.
 */
@Composable
actual fun SettingsNavGraph(navCallbacks: NavCallbacks, modifier: Modifier) {
    val backStack: NavBackStack<Settings> = rememberInMemoryNavBackStack(Settings)

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
            entryProvider = entryProvider {
                entry<Settings> { SettingsScreen() }
            },
        )
    }
}
