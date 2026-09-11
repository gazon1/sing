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
 * Creates the multi-back-stack [Nav3State] for JVM Desktop.
 *
 * On JVM, [rememberNavBackStack] requires a [SavedStateConfiguration] so the
 * navigation library can serialize back-stack state if needed. On Desktop the
 * state is kept in memory only (no process-death persistence), so we use the
 * default (empty) serializers module. If serialization is needed later,
 * add [kotlinx.serialization.modules.polymorphic] registrations for all
 * [AppDestination] subclasses.
 */
@Composable
fun rememberJvmNav3State(): Nav3State {
    val startRoute: NavKey = AppDestination.Today
    val topLevelRoutes: Set<NavKey> =
        DestinationKind.tabs.toSet() + DestinationKind.menuEntries.toSet()

    val topLevelRouteState: MutableState<NavKey> = remember(startRoute) {
        mutableStateOf(startRoute)
    }

    // Use the default (empty) serializers module. The state is not persisted on Desktop.
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
