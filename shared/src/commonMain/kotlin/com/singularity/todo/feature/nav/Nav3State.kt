package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import com.singularity.todo.feature.nav.deeplink.DeepLinkTarget
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

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
     * Открывает deep link: переключает вкладку и пересобирает её стек как `[tab, screen]`.
     *
     * Синтетический back stack даёт осмысленный «назад» — на родительский экран
     * вкладки. Без него ссылка открывала бы экран в стеке той вкладки, на которой
     * приложение оказалось, и «назад» уводил бы в неизвестно куда.
     *
     * Ссылка применяется мимо [NavigationPolicy] намеренно: политика описывает
     * переходы *внутри* приложения, а deep link — вход извне с явным родителем.
     *
     * @param target цель ссылки: вкладка и открываемый экран.
     */
    fun openDeepLink(target: DeepLinkTarget) {
        val stack = requireBackStackFor(target.tab)
        stack.clear()
        stack.add(target.tab)
        stack.add(target.screen)
        setPreviousTopLevelRoute(startRoute)
        topLevelRoute = target.tab
    }

    /**
     * Converts all active back stacks into a flat list of [NavEntry] objects,
     * decorated with the ViewModelStore and SaveableStateHolder decorators.
     *
     * @param entryProvider A function that returns a [NavEntry] for each route key.
     */
    @Composable
    fun toDecoratedEntries(entryProvider: (AppDestination) -> NavEntry<AppDestination>): List<NavEntry<NavKey>> =
        toDecoratedEntries(entryProvider, persistentListOf())

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
        entryDecorators: ImmutableList<androidx.navigation3.runtime.NavEntryDecorator<NavKey>>,
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
        val result = getTopLevelRoutesInUse().flatMap {
            decoratedEntries[it]
                ?: emptyList()
        }
        // Fallback: never yield an empty entry list, which would render a blank
        // shell. If every resolved stack is empty, reseed the start stack and
        // return its entries.
        return if (result.isEmpty()) {
            val startStack = backStackFor(startRoute)
            if (startStack != null && startStack.isEmpty()) {
                startStack.add(startRoute)
            }
            decoratedEntries[startRoute] ?: emptyList()
        } else {
            result
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

/**
 * Topmost route in the current stack — the deepest screen the user is on.
 * Used by both shells to decide the FAB and back behavior.
 */
fun Nav3State.topMostRoute(): NavKey =
    requireBackStackFor(topLevelRoute).lastOrNull() ?: topLevelRoute

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
    fun close() {
        goBack()
    }

    /** True when [goBack] would perform a navigation instead of signalling "at root". */
    fun canGoBack(): Boolean =
        state.requireBackStackFor(state.topLevelRoute).size > 1 ||
            state.previousTopLevelRoute != state.topLevelRoute

    /**
     * Go back in the current stack.
     *
     * - If a nested screen is on top: pop it.
     * - If at the tab root: return to the previously active tab ([previousTopLevelRoute]).
     *   This prevents silent teleportation to [startRoute] when the user presses Back
     *   at the root of a non-default tab (e.g., Upcoming).
     *
     * Returns `true` when a back navigation was performed, `false` when already at
     * the ultimate root (no nested screen, no previous tab to return to) — callers
     * that own a window/activity can then close it.
     */
    fun goBack(): Boolean {
        val currentStack = state.requireBackStackFor(state.topLevelRoute)
        val currentRoute = currentStack.lastOrNull()
            ?: run {
                // Fallback: an empty stack would leave the shell blank; reseed.
                currentStack.add(state.topLevelRoute)
                return false
            }

        if (currentRoute == state.topLevelRoute) {
            // At a tab root: only switch tabs when there is a distinct previous tab.
            if (state.previousTopLevelRoute == state.topLevelRoute) {
                return false
            }
            state.topLevelRoute = state.previousTopLevelRoute
            // Fallback: never activate an empty stack.
            val previousStack = state.requireBackStackFor(state.topLevelRoute)
            if (previousStack.isEmpty()) {
                previousStack.add(state.topLevelRoute)
            }
        } else {
            currentStack.removeLastOrNull()
        }
        // Fallback: never leave the active stack empty.
        if (currentStack.isEmpty()) {
            currentStack.add(state.topLevelRoute)
        }
        return true
    }
}
