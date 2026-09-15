package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.savedstate.serialization.SavedStateConfiguration
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.DestinationKind
import com.singularity.todo.feature.nav.Nav3State

/**
 * Android implementation of [rememberNav3State].
 *
 * Uses [SavedStateConfiguration] { } so each tab's back-stack survives process death.
 * Previously this function used the no-arg [rememberNavBackStack] overload (no
 * SavedStateConfiguration), meaning back stacks were lost on rotation and process death.
 * That was a latent bug; this fix aligns Android with the JVM behaviour and with
 * the Koin per-entry VM scoping fix (ADR 2026-09-14).
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
