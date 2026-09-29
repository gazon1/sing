package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.appNavSavedStateConfig

/**
 * Android implementation of [rememberNav3State].
 *
 * Uses [appNavSavedStateConfig] to construct a [SavedStateConfiguration] that registers every
 * [AppDestination] concrete subtype at the [NavKey] polymorphic level. This enables
 * `rememberNavBackStack` to serialize the back stack across process death and configuration
 * changes via the standard Compose saved-state mechanism.
 *
 * The configuration is a no-op on Desktop (where it would be dead code), which is why
 * the JVM implementation uses [rememberInMemoryNavBackStack] instead — see
 * [Nav3StateFactory.jvm.kt].
 *
 * @see navSavedStateConfig
 */
@Composable
actual fun rememberNav3State(): Nav3State {
    // MUST be a member of `topLevelRoutes` — `backStacks` is built from that set, so a
    // startRoute outside it would have no back stack and NavDisplay would receive an
    // empty entry list. `DestinationKind.tabs` uses AgendaGraph(...) instances.
    val startRoute: NavKey = AppDestination.AgendaGraph(AgendaStartRoute.Today)
    val topLevelRoutes: Set<NavKey> =
        DestinationKind.tabs.toSet() + DestinationKind.menuEntries.toSet()

    val topLevelRouteState: MutableState<NavKey> = remember(startRoute) {
        mutableStateOf(startRoute)
    }

    val savedStateConfig = appNavSavedStateConfig

    val backStacks = topLevelRoutes.associateWith { key ->
        rememberNavBackStack(savedStateConfig, key)
    }

    return remember(startRoute, topLevelRoutes) {
        Nav3State(startRoute, topLevelRouteState, backStacks)
    }
}
