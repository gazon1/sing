package com.singularity.todo.feature.nav

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The shell must never hand `NavDisplay` an empty entry list — it throws
 * `IllegalArgumentException: entries cannot be empty` (see the [Nav3State] KDoc) — and it
 * must never rescue an empty stack by showing a *different* tab.
 *
 * [Nav3State.toDecoratedEntries] builds its entries from [Nav3State.renderableKeysFor],
 * one call per top-level route, and returns the entries of the routes in use. A non-empty
 * key list per route therefore is the invariant that keeps the entry list non-empty; these
 * tests pin it, including for the frame before the stack is reseeded.
 */
@Tag("fast")
class Nav3StateRenderableKeysTest {
    private val today: AppNavKey = AppDestination.AgendaGraph(AgendaStartRoute.Today)
    private val upcoming: AppNavKey = AppDestination.AgendaGraph(AgendaStartRoute.Upcoming)

    private fun state(): Nav3State =
        Nav3State(
            startRoute = today,
            topLevelRouteState = mutableStateOf(today),
            backStacks = mapOf(today to NavBackStack(today), upcoming to NavBackStack(upcoming)),
        )

    @Test
    fun `an empty start stack yields its own route instead of no entries`() {
        val state = state()
        state.requireBackStackFor(today).clear()

        assertEquals(listOf(today), state.renderableKeysFor(today))
    }

    @Test
    fun `an empty stack for the active tab yields that tab, not the start tab`() {
        val state = state()
        state.topLevelRoute = upcoming
        state.requireBackStackFor(upcoming).clear()

        assertEquals(listOf(upcoming), state.renderableKeysFor(upcoming))
        assertFalse(today in state.renderableKeysFor(upcoming))
    }

    @Test
    fun `a seeded stack yields its own keys, unchanged`() {
        val state = state()
        state.requireBackStackFor(today).add(AppDestination.Settings)

        assertEquals(listOf(today, AppDestination.Settings), state.renderableKeysFor(today))
    }
}
