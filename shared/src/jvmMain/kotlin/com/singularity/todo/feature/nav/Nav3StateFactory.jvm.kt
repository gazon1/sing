package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavKey

/**
 * JVM Desktop implementation of [rememberNav3State].
 *
 * Uses [rememberInMemoryNavBackStack] — a plain `remember { NavBackStack(key) }`.
 *
 * **Why no SavedStateConfiguration on Desktop:** `LocalSaveableStateRegistry` always resolves
 * to `null` on the JVM Desktop (the `savedstate-compose-desktop` artifact is deliberately empty
 * — a Kotlin/JVM interop workaround). This means `SavedStateConfiguration` is dead code:
 * `rememberSerializable` becomes indistinguishable from plain `remember`, and the
 * `polymorphic(NavKey::class) { subclass(...) }` block inside it is never consulted.
 * Additionally, process death does not exist on Desktop, so there is nothing to persist.
 *
 * See `docs/decisions/2026-09-16-nav3-desktop-in-memory-no-savedstate.md`.
 *
 * @see rememberInMemoryNavBackStack
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

    val backStacks = topLevelRoutes.associateWith { key ->
        // No process death on JVM Desktop. An in-memory NavBackStack survives as long as the
        // Compose composition is alive. LocalSaveableStateRegistry == null here, so any
        // SavedStateConfiguration would be dead code AND a polymorphic-serialization foot-gun.
        rememberInMemoryNavBackStack(key)
    }

    return remember(startRoute, topLevelRoutes) {
        Nav3State(startRoute, topLevelRouteState, backStacks)
    }
}
