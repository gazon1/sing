package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.savedstate.serialization.SavedStateConfiguration

/**
 * JVM Desktop implementation of [rememberNav3State].
 *
 * Mirrors the former [rememberJvmNav3State] body from [JvmNav3State.kt]
 * (now deleted — this file replaces it). Uses [SavedStateConfiguration] { }
 * for API compatibility; on Desktop the state is kept in memory only.
 */
@Composable
actual fun rememberNav3State(): Nav3State {
    val startRoute: NavKey = AppDestination.Today
    val topLevelRoutes: Set<NavKey> =
        DestinationKind.tabs.toSet() + DestinationKind.menuEntries.toSet()

    val topLevelRouteState: MutableState<NavKey> = remember(startRoute) {
        mutableStateOf(startRoute)
    }

    val savedStateConfig = remember {
        SavedStateConfiguration { }
    }

    val backStacks = topLevelRoutes.associateWith { key ->
        rememberNavBackStack(savedStateConfig, key)
    }

    return remember(startRoute, topLevelRoutes) {
        Nav3State(startRoute, topLevelRouteState, backStacks)
    }
}
