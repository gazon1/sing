package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator

/**
 * State holder for Navigation 3 multi-back-stack navigation.
 * Shared across Android and Desktop JVM.
 *
 * Port of the official NavigationState from terrakok nav3-recipes multiplestacks recipe.
 *
 * @param startRoute The initial top-level route.
 * @param topLevelRouteState MutableState wrapping the current top-level route.
 * @param backStacks Map from each top-level route to its back stack.
 */
class Nav3State internal constructor(
    val startRoute: NavKey,
    private val topLevelRouteState: MutableState<NavKey>,
    private val backStacks: Map<NavKey, NavBackStack<NavKey>>,
) {
    /** The set of all top-level routes (tabs + menu entries). */
    val topLevelRoutes: Set<NavKey> = backStacks.keys

    /** Get the back stack for a given top-level route. */
    fun backStackFor(route: NavKey): NavBackStack<NavKey>? =
        backStacks[route]

    var topLevelRoute: NavKey
        get() = topLevelRouteState.value
        set(value) {
            topLevelRouteState.value = value
        }

    /**
     * Converts all active back stacks into a flat list of [NavEntry] objects,
     * decorated with [rememberSaveableStateHolderNavEntryDecorator] for state preservation.
     *
     * @param entryProvider A function that returns a [NavEntry] for each route key.
     */
    @Composable
    fun toDecoratedEntries(entryProvider: (AppDestination) -> NavEntry<AppDestination>): List<NavEntry<NavKey>> =
        toDecoratedEntries(entryProvider, emptyList())

    /**
     * Converts all active back stacks into a flat list of [NavEntry] objects,
     * decorated with the provided [entryDecorators] for state preservation and ViewModel scoping.
     *
     * @param entryProvider A function that returns a [NavEntry] for each route key.
     * @param entryDecorators Additional [NavEntryDecorator]s to apply (e.g. [rememberViewModelStoreNavEntryDecorator]).
     */
    @Composable
    fun toDecoratedEntries(
        entryProvider: (AppDestination) -> NavEntry<AppDestination>,
        entryDecorators: List<androidx.navigation3.runtime.NavEntryDecorator<NavKey>>,
    ): List<NavEntry<NavKey>> {
        val decoratedEntries = backStacks.mapValues { (_, stack) ->
            val decorators = buildList {
                addAll(entryDecorators)
                // Always include SaveableStateHolder for state preservation across tab swaps
                add(rememberSaveableStateHolderNavEntryDecorator())
            }
            rememberDecoratedNavEntries(
                backStack = stack,
                entryDecorators = decorators,
                entryProvider = { key: NavKey ->
                    @Suppress("UNCHECKED_CAST") entryProvider(key as AppDestination) as NavEntry<NavKey>
                },
            )
        }
        return getTopLevelRoutesInUse().flatMap {
                decoratedEntries[it]
                    ?: emptyList()
            }
    }

    private fun getTopLevelRoutesInUse(): List<NavKey> =
        if (topLevelRoute == startRoute) {
            listOf(startRoute)
        } else {
            listOf(startRoute, topLevelRoute)
        }
}

/**
 * Handles navigation events (forward and back) by updating [state].
 *
 * @param state The [Nav3State] instance to mutate.
 */

/**
 * Navigation callbacks passed to [createAppEntryProvider] / [createJvmEntryProvider] so entries
 * can trigger navigation without needing a [Navigator] instance (avoids internal class visibility
 * issues). Placed here so both androidMain and jvmMain can import it.
 */
data class NavCallbacks(val navigate: (AppDestination) -> Unit, val goBack: () -> Unit)

class Navigator(private val state: Nav3State) {
    /**
     * Navigate to [route]. If [route] is a top-level route, switch to it.
     * Otherwise push it onto the current stack.
     */
    fun navigate(route: NavKey) {
        if (route in state.topLevelRoutes) {
            state.topLevelRoute = route
        } else {
            state.backStackFor(state.topLevelRoute)
                ?.add(route)
        }
    }

    /** Go back in the current stack. If at the bottom, return to [state.startRoute]. */
    fun goBack() {
        val currentStack = state.backStackFor(state.topLevelRoute)
            ?: error("Stack for ${state.topLevelRoute} not found")
        val currentRoute = currentStack.lastOrNull()
            ?: return

        if (currentRoute == state.topLevelRoute) {
            state.topLevelRoute = state.startRoute
        } else {
            currentStack.removeLastOrNull()
        }
    }
}
