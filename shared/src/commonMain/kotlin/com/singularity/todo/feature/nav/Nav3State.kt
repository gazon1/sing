package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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

    /**
     * Emits the top-level route each time the user taps the tab they are already on.
     *
     * Tapping the active tab is a no-op for navigation — [topLevelRoute] is already that
     * route, so no state change fires and the tab bar looks unresponsive. Screens use this
     * to reset their scroll position on reselect, matching the platform behaviour users
     * expect from a bottom bar.
     *
     * `extraBufferCapacity = 1` with [BufferOverflow.DROP_OLDEST] is the upstream recipe:
     * a reselect arriving while no screen is collecting (the tab is not composed) must not
     * suspend the tap handler or grow an unbounded queue.
     */
    private val _reselectEvents = MutableSharedFlow<NavKey>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Reselect events for the active tab. See [_reselectEvents]. */
    val reselectEvents: SharedFlow<NavKey> = _reselectEvents.asSharedFlow()

    /** The previously active top-level route (before the last tab switch). */
    val previousTopLevelRoute: NavKey get() = _previousTopLevelRoute

    /** Sets the previous top-level route. Used by [Navigator.navigate] to track tab history. */
    internal fun setPreviousTopLevelRoute(route: NavKey) {
        _previousTopLevelRoute = route
    }

    /**
     * Handles a tab-bar tap. Reselecting the active tab emits [reselectEvents] instead of
     * navigating; tapping a different tab switches to it.
     */
    internal fun onTabTapped(route: NavKey) {
        if (route == topLevelRoute) {
            _reselectEvents.tryEmit(route)
        } else {
            setPreviousTopLevelRoute(topLevelRoute)
            topLevelRoute = route
        }
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

    // Targeted, not "all non-empty stacks": every back stack is seeded with its key at
    // composition, so an isNotEmpty filter would always include every top-level route and
    // NavDisplay would render the last one (Settings) instead of the current tab.
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
 *
 * @param navigate Delegates to [Navigator.open] — every request is resolved by
 *   [NavigationPolicy] (REQ-NAV-001), so per-feature navigators and entry providers keep
 *   their typed-`AppDestination` signature while the decision stays in one place.
 * @param close Delegates to [Navigator.close] — the `dest == null` branch of a nested
 *   graph's `onExitGraph` (exit the graph, no destination).
 */
data class NavCallbacks(val navigate: (AppDestination) -> Unit, val goBack: () -> Unit, val close: () -> Unit) {
    /**
     * The uniform `onExitGraph` callback every nested graph and platform entry provider
     * wires up — the former per-graph `when(dest)` allow-lists, collapsed to one form:
     *
     * ```kotlin
     * onExitGraph = nav.graphExit   // null → close(), else navigate() (policy-resolved)
     * ```
     *
     * A destination that used to fall through an allow-list now opens (REQ-NAV-002)
     * instead of silently degrading to back-navigation.
     */
    val graphExit: (AppDestination?) -> Unit = { dest ->
        if (dest == null) close() else navigate(dest)
    }
}

class Navigator(private val state: Nav3State) {
    /**
     * Open [target] — the single facade every request goes through (REQ-NAV-001).
     *
     * Derives the current context from the top of the active stack and hands the pair to
     * [NavigationPolicy]:
     *
     * - [OpenAction.SwitchTab] → activate the target's top-level destination (reselect
     *   semantics included — see [Nav3State.onTabTapped]);
     * - [OpenAction.Push] / [OpenAction.ExitAndOpen] → push onto the current stack, the
     *   origin staying underneath (back returns to it);
     * - a bare nested start route throws before any mutation, leaving the stack unchanged.
     */
    fun open(target: AppNavKey) {
        val stack = state.requireBackStackFor(state.topLevelRoute)
        val from = (stack.lastOrNull() ?: state.topLevelRoute) as? AppNavKey
            ?: error(
                "Navigator.open: current context ${stack.lastOrNull()} of " +
                    "top-level ${state.topLevelRoute} is not an AppNavKey.",
            )

        when (NavigationPolicy.resolve(from, target)) {
            OpenAction.SwitchTab -> state.onTabTapped(target)

            OpenAction.Push,
            OpenAction.ExitAndOpen,
            -> stack.add(target)
        }
    }

    /**
     * Exit the current nested graph with no destination — the `dest == null` branch of a
     * graph's `onExitGraph`. Same mechanics as [goBack]: pop the graph's entry, or at a
     * top-level root return to the previously active destination.
     */
    fun close() = goBack()

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
