package com.singularity.todo.feature.nav

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The state may come up on a route other than the start route.
 *
 * A rotation restores the tab the user was on, so the state is built with the
 * restored route already in it. What the state has to guarantee is not "the
 * start route" but "a route that has a back stack" -- the screen the user was
 * looking at is the one that has to come back.
 */
@Tag("fast")
class Nav3StateRestoredRouteTest {
    private val today: AppNavKey = AppDestination.AgendaGraph(AgendaStartRoute.Today)
    private val inbox: AppNavKey = AppDestination.AgendaGraph(AgendaStartRoute.Inbox)

    @Test
    fun `a restored route other than the start route is accepted`() {
        val state =
            Nav3State(
                startRoute = today,
                topLevelRouteState = mutableStateOf(inbox),
                backStacks = mapOf(today to NavBackStack(today), inbox to NavBackStack(inbox)),
            )

        assertEquals(inbox, state.topLevelRoute)
    }

    @Test
    fun `a route without a back stack is refused`() {
        assertFailsWith<IllegalArgumentException> {
            Nav3State(
                startRoute = today,
                topLevelRouteState = mutableStateOf(AppDestination.Settings),
                backStacks = mapOf(today to NavBackStack(today), inbox to NavBackStack(inbox)),
            )
        }
    }
}
