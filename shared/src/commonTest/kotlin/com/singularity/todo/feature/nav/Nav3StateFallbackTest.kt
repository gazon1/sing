package com.singularity.todo.feature.nav

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("fast")
class Nav3StateFallbackTest {
    private val today: AppNavKey = AppDestination.AgendaGraph(AgendaStartRoute.Today)
    private val upcoming: AppNavKey = AppDestination.AgendaGraph(AgendaStartRoute.Upcoming)

    @Test
    fun `goBack reseeds an empty stack instead of leaving it empty`() {
        val state =
            Nav3State(
                startRoute = today,
                topLevelRouteState = mutableStateOf(today),
                backStacks = mapOf(today to NavBackStack(today)),
            )
        val navigator = Navigator(state)
        state.requireBackStackFor(today).clear()
        assertFalse(navigator.goBack())
        assertEquals(listOf(today), state.requireBackStackFor(today).toList())
    }

    @Test
    fun `goBack returns false at ultimate root`() {
        val state =
            Nav3State(
                startRoute = today,
                topLevelRouteState = mutableStateOf(today),
                backStacks = mapOf(today to NavBackStack(today)),
            )
        assertFalse(Navigator(state).goBack())
    }

    @Test
    fun `goBack pops a nested screen and returns true`() {
        val state =
            Nav3State(
                startRoute = today,
                topLevelRouteState = mutableStateOf(today),
                backStacks = mapOf(today to NavBackStack(today)),
            )
        state.requireBackStackFor(today).add(AppDestination.Settings)
        assertTrue(Navigator(state).goBack())
        assertEquals(listOf(today), state.requireBackStackFor(today).toList())
    }

    @Test
    fun `goBack after deep link returns to previous tab`() {
        val state =
            Nav3State(
                startRoute = today,
                topLevelRouteState = mutableStateOf(today),
                backStacks = mapOf(today to NavBackStack(today), upcoming to NavBackStack(upcoming)),
            )
        state.openDeepLink(
            com.singularity.todo.feature.nav.deeplink
                .DeepLinkTarget(upcoming, AppDestination.Settings),
        )
        assertTrue(Navigator(state).goBack()) // pops the deep-linked screen
        assertEquals(1, state.requireBackStackFor(upcoming).size)
        assertTrue(Navigator(state).goBack()) // returns to previous tab
        assertEquals(today, state.topLevelRoute)
    }

    @Test
    fun `goBack reseeds an empty previous stack when switching tabs`() {
        val state =
            Nav3State(
                startRoute = today,
                topLevelRouteState = mutableStateOf(today),
                backStacks = mapOf(today to NavBackStack(today), upcoming to NavBackStack(upcoming)),
            )
        // The previous tab's stack is empty: it must be reseeded, not left blank.
        state.requireBackStackFor(today).clear()
        state.setPreviousTopLevelRoute(today)
        state.topLevelRoute = upcoming
        assertTrue(Navigator(state).goBack())
        assertEquals(today, state.topLevelRoute)
        assertEquals(listOf(today), state.requireBackStackFor(today).toList())
    }

    @Test
    fun `goBack reseeds the active stack after popping a stack that held no tab key`() {
        val state =
            Nav3State(
                startRoute = today,
                topLevelRouteState = mutableStateOf(today),
                backStacks = mapOf(today to NavBackStack(today), upcoming to NavBackStack(upcoming)),
            )
        state.topLevelRoute = upcoming
        // The malformed stack the fallback exists for: a nested screen without the tab key.
        state.requireBackStackFor(upcoming).clear()
        state.requireBackStackFor(upcoming).add(AppDestination.Settings)

        assertTrue(Navigator(state).goBack())

        // The pop emptied the active stack: it must be reseeded, not left blank.
        assertEquals(listOf(upcoming), state.requireBackStackFor(upcoming).toList())
        // The tab did not change: the active top-level route is what reseeded the stack.
        assertEquals(upcoming, state.topLevelRoute)
    }

    @Test
    fun `canGoBack answers whether a back press navigates`() {
        // The ultimate root: one tab key, no nested screen, and the previous tab is this tab.
        val root =
            Nav3State(
                startRoute = today,
                topLevelRouteState = mutableStateOf(today),
                backStacks = mapOf(today to NavBackStack(today)),
            )
        root.setPreviousTopLevelRoute(today)
        assertFalse(Navigator(root).canGoBack())

        // An empty active stack is no nested screen either.
        root.requireBackStackFor(today).clear()
        assertFalse(Navigator(root).canGoBack())

        // A nested screen on top of the active stack: back navigates.
        val nested =
            Nav3State(
                startRoute = today,
                topLevelRouteState = mutableStateOf(today),
                backStacks = mapOf(today to NavBackStack(today)),
            )
        nested.requireBackStackFor(today).add(AppDestination.Settings)
        assertTrue(Navigator(nested).canGoBack())

        // A distinct previous tab: back returns to that tab.
        val withPreviousTab =
            Nav3State(
                startRoute = today,
                topLevelRouteState = mutableStateOf(today),
                backStacks = mapOf(today to NavBackStack(today), upcoming to NavBackStack(upcoming)),
            )
        withPreviousTab.setPreviousTopLevelRoute(upcoming)
        assertTrue(Navigator(withPreviousTab).canGoBack())

        // Malformed single-element non-tab-key active stack: back pops/reseeds.
        val malformed =
            Nav3State(
                startRoute = today,
                topLevelRouteState = mutableStateOf(today),
                backStacks = mapOf(today to NavBackStack(today), upcoming to NavBackStack(upcoming)),
            )
        malformed.topLevelRoute = upcoming
        malformed.setPreviousTopLevelRoute(upcoming) // previous == top: only the non-tab top makes it navigable
        malformed.requireBackStackFor(upcoming).clear()
        malformed.requireBackStackFor(upcoming).add(AppDestination.Settings)
        assertTrue(Navigator(malformed).canGoBack())
    }

    @Test
    fun `goBack switches to previous tab and returns true`() {
        val state =
            Nav3State(
                startRoute = today,
                topLevelRouteState = mutableStateOf(today),
                backStacks = mapOf(today to NavBackStack(today), upcoming to NavBackStack(upcoming)),
            )
        // simulate a tab switch: previous becomes today, current becomes upcoming
        state.setPreviousTopLevelRoute(today)
        state.topLevelRoute = upcoming
        assertTrue(Navigator(state).goBack())
        assertEquals(today, state.topLevelRoute)
    }
}
