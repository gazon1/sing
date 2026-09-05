package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

/**
 * Thin wrapper around [NavHostController] that enforces our navigation contract:
 *
 * 1. [navigateTopLevel] — for bottom-bar / drawer destinations.
 *    - `popUpTo(startDestination)` with `saveState = true` so per-destination
 *      backstacks are preserved when switching tabs (see reference plan §19).
 *    - `launchSingleTop = true` — avoid stacking duplicates on repeat taps.
 *    - `restoreState = true` — when returning to a tab, restore its scroll/UI state.
 *
 * 2. [navigate] — for sub-routes pushed on top of a top-level destination
 *    (e.g. open TaskDetail from Today). No special flags — standard push.
 *
 * Why a wrapper and not bare [NavHostController]:
 * - The contract is greppable in one place.
 * - Tests can construct an [AppNavigator] over a real `TestNavHostController`
 *   and verify the contract (no mocks).
 * - `@Stable` lets Compose skip recomposition when state hasn't changed.
 */
@Stable
class AppNavigator(
    val controller: NavHostController,
) {
    /**
     * Navigate to a bottom-bar / drawer destination. Use this for top-level tabs.
     *
     * Behaviour:
     * - Pops back to the graph's start destination, **saving** each popped entry.
     * - Launches as single top (repeat taps don't stack).
     * - Restores previously saved state for the destination.
     *
     * Idempotent: tapping the current tab does nothing visible.
     */
    fun navigateTopLevel(destination: AppDestination) {
        require(DestinationKind.isTab(destination) || DestinationKind.isMenuEntry(destination)) {
            "navigateTopLevel expects a tab or menu destination, got $destination"
        }
        controller.navigate(destination) {
            popUpTo(controller.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    /**
     * Push a sub-route on top of the current top-level destination.
     * Plain forward navigation — no save/restore.
     */
    fun navigate(destination: AppDestination) {
        controller.navigate(destination)
    }

    /**
     * Navigate up the back stack. Returns `true` if popped, `false` if at the start.
     */
    fun popBackStack(): Boolean = controller.popBackStack()

    /**
     * Compose-friendly accessor that returns the current top-level destination,
     * defaulting to [AppDestination.Today] (our start destination) when null
     * or when the current entry is a sub-route.
     *
     * Use this for "highlight the active tab in the bottom bar" — never null,
     * so the bar always has a valid selection.
     */
    @Composable
    fun currentTopLevelDestination(): AppDestination {
        val entry by controller.currentBackStackEntryAsState()
        // Sub-routes push on top of a top-level destination. The top-level
        // destination is the parent entry's route — but for our graph the
        // start destination is Today and tabs don't nest sub-routes of OTHER
        // tabs, so the destination's own class is enough.
        val route = entry?.destination?.route ?: return AppDestination.Today
        return destinationFromRoute(route) ?: AppDestination.Today
    }

    companion object {
        /**
         * Materialize a navigator in a Composable scope.
         *
         * For tests, construct an [AppNavigator] directly with a
         * `TestNavHostController` — see `AppNavigatorTest`.
         */
        @Composable
        fun remember(): AppNavigator {
            val controller = rememberNavController()
            return remember(controller) { AppNavigator(controller) }
        }
    }
}

/**
 * Decode a route identifier (e.g. `com.singularity.todo.feature.nav.AppDestination.Inbox`)
 * back to its [AppDestination] instance. Returns `null` if the route is unknown.
 *
 * The route string format is produced by the Navigation Compose type-safe
 * KSerializer — it embeds the fully-qualified sealed-interface name and the
 * concrete subclass name, separated by a dot.
 */
private fun destinationFromRoute(route: String): AppDestination? {
    val suffix = route.substringAfterLast('.', missingDelimiterValue = "")
    return when (suffix) {
        "Inbox" -> AppDestination.Inbox
        "Today" -> AppDestination.Today
        "Plans" -> AppDestination.Plans
        "Habits" -> AppDestination.Habits
        "Calendar" -> AppDestination.Calendar
        "Notes" -> AppDestination.Notes
        "AiChat" -> AppDestination.AiChat
        "Search" -> AppDestination.Search
        "Archive" -> AppDestination.Archive
        "Settings" -> AppDestination.Settings
        else -> null
    }
}

/**
 * Top-level Composable entry point — used by [com.singularity.todo.App].
 *
 * Kept here (next to the navigator) so the public API is discoverable:
 * "I need navigation → `rememberAppNavigator()`."
 */
@Composable
fun rememberAppNavigator(): AppNavigator = AppNavigator.remember()
