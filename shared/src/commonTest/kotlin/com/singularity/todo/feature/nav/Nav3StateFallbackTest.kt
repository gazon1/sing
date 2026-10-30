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
        val state = Nav3State(
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
        val state = Nav3State(
            startRoute = today,
            topLevelRouteState = mutableStateOf(today),
            backStacks = mapOf(today to NavBackStack(today)),
        )
        assertFalse(Navigator(state).goBack())
    }

    @Test
    fun `goBack pops a nested screen and returns true`() {
        val state = Nav3State(
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
        val state = Nav3State(
            startRoute = today,
            topLevelRouteState = mutableStateOf(today),
            backStacks = mapOf(today to NavBackStack(today), upcoming to NavBackStack(upcoming)),
        )
        state.openDeepLink(com.singularity.todo.feature.nav.deeplink.DeepLinkTarget(upcoming, AppDestination.Settings))
        assertTrue(Navigator(state).goBack()) // pops the deep-linked screen
        assertEquals(1, state.requireBackStackFor(upcoming).size)
        assertTrue(Navigator(state).goBack()) // returns to previous tab
        assertEquals(today, state.topLevelRoute)
    }

    @Test
    fun `goBack switches to previous tab and returns true`() {
        val state = Nav3State(
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
