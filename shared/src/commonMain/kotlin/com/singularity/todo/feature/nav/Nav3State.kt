package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
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
 * @param startRoute The initial top-level route. Must be a key of [backStacks] — the
 *   multiplestacks pattern looks the start stack up by this route, and `NavDisplay` throws
 *   `IllegalArgumentException: entries cannot be empty` when the lookup misses.
 * @param topLevelRouteState MutableState wrapping the current top-level route.
 * @param backStacks Map from each top-level route to its back stack.
 */
class Nav3State internal constructor(
    val startRoute: NavKey,
    private val topLevelRouteState: MutableState<NavKey>,
    private val backStacks: Map<NavKey, NavBackStack<NavKey>>,
    initialPreviousTopLevelRoute: NavKey = startRoute,
) {
    private var _previousTopLevelRoute: NavKey = initialPreviousTopLevelRoute

    /** The previously active top-level route (before the last tab switch). */
    val previousTopLevelRoute: NavKey get() = _previousTopLevelRoute

    /** Sets the previous top-level route. Used by [Navigator.navigate] to track tab history. */
    internal fun setPreviousTopLevelRoute(route: NavKey) {
        _previousTopLevelRoute = route
    }
    init {
        require(startRoute in backStacks) {
            "Nav3State: startRoute=$startRoute has no back stack. " +
                "It must be one of the top-level routes (${backStacks.keys})."
        }
        require(startRoute == topLevelRouteState.value) {
            "Nav3State: topLevelRouteState must initialise to startRoute=$startRoute, " +
                "but was ${topLevelRouteState.value}."
        }
    }

    /** The set of all top-level routes (tabs + menu entries). */
    val topLevelRoutes: Set<NavKey> = backStacks.keys

    /** Get the back stack for a given top-level route. */
    fun backStackFor(route: NavKey): NavBackStack<NavKey>? = backStacks[route]

    /**
     * Get the back stack for a given top-level route, or fail.
     *
     * Use this instead of [backStackFor] at call sites that cannot meaningfully continue
     * without a stack — a silent `null` there turns into an empty `NavDisplay` entry list.
     */
    fun requireBackStackFor(route: NavKey): NavBackStack<NavKey> = checkNotNull(backStackFor(route)) {
        "Nav3State: no back stack for route=$route. Top-level routes: ${backStacks.keys}."
    }

    var topLevelRoute: NavKey
        get() = topLevelRouteState.value
        set(value) {
            topLevelRouteState.value = value
        }

    /**
     * Converts all active back stacks into a flat list of [NavEntry] objects,
     * decorated with the ViewModelStore and SaveableStateHolder decorators.
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
     * [rememberViewModelStoreNavEntryDecorator] is always applied, before any caller-supplied
     * decorators. Without it every entry resolves the *same* `LocalViewModelStoreOwner` — the
     * window/activity — so `koinViewModel` hands the first-created instance to every subsequent
     * screen. The agenda then kept evaluating the definition it was built with: switching to
     * Inbox recomposed `AgendaScreen(AgendaPresets.Inbox)` but reused the boot-time
     * `AgendaViewModel`, so no tab ever re-evaluated its own sections.
     *
     * @param entryProvider A function that returns a [NavEntry] for each route key.
     * @param entryDecorators Additional [NavEntryDecorator]s to apply.
     */
    @Composable
    fun toDecoratedEntries(
        entryProvider: (AppDestination) -> NavEntry<AppDestination>,
        entryDecorators: List<androidx.navigation3.runtime.NavEntryDecorator<NavKey>>,
    ): List<NavEntry<NavKey>> {
        val viewModelStoreDecorator = rememberViewModelStoreNavEntryDecorator<NavKey>()
        val decoratedEntries = backStacks.mapValues { (_, stack) ->
            val decorators = buildList {
                add(viewModelStoreDecorator)
                addAll(entryDecorators)
                // Always include SaveableStateHolder for state preservation across tab swaps.
                // The ViewModelStore decorator requires it to provide SavedStateHandle.
                add(rememberSaveableStateHolderNavEntryDecorator())
            }
            rememberDecoratedNavEntries(
                backStack = stack,
                entryDecorators = decorators,
                entryProvider = { key: NavKey ->
                    @Suppress("UNCHECKED_CAST")
                    entryProvider(key as AppDestination) as NavEntry<NavKey>
                },
            )
        }
        return getTopLevelRoutesInUse().flatMap {
            decoratedEntries[it]
                ?: emptyList()
        }
    }

    private fun getTopLevelRoutesInUse(): List<NavKey> = if (topLevelRoute == startRoute) {
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
            state.setPreviousTopLevelRoute(state.topLevelRoute)
            state.topLevelRoute = route
        } else {
            state.requireBackStackFor(state.topLevelRoute).add(route)
        }
    }

    /**
     * Go back in the current stack.
     *
     * - If a nested screen is on top: pop it.
     * - If at the tab root: return to the previously active tab ([previousTopLevelRoute]).
     *   This prevents silent teleportation to [startRoute] when the user presses Back
     *   at the root of a non-default tab (e.g., Upcoming).
     */
    fun goBack() {
        val currentStack = state.requireBackStackFor(state.topLevelRoute)
        val currentRoute = currentStack.lastOrNull()
            ?: return

        if (currentRoute == state.topLevelRoute) {
            state.topLevelRoute = state.previousTopLevelRoute
        } else {
            currentStack.removeLastOrNull()
        }
    }
}
