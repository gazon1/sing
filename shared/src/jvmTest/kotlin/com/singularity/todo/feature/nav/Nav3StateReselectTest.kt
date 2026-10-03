package com.singularity.todo.feature.nav

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for [Nav3State] tab switching and reselect events.
 *
 * Pure JVM — [Nav3State] itself is not a composable, only [toDecoratedEntries] is, so the
 * constructor can be driven directly with plain `NavBackStack` instances.
 */
class Nav3StateReselectTest {

    private val start: NavKey = AppDestination.AgendaGraph(AgendaStartRoute.Today)
    private val other: NavKey = AppDestination.NotesGraph()

    private fun state(vararg routes: NavKey): Nav3State {
        val all = listOf(start) + routes
        return Nav3State(
            startRoute = start,
            topLevelRouteState = mutableStateOf(start),
            backStacks = all.associateWith { NavBackStack(it) },
        )
    }

    // ── Construction invariants ────────────────────────────────────────────

    @Test
    fun `startRoute must have a back stack`() {
        assertFailsWith<IllegalArgumentException> {
            Nav3State(
                startRoute = start,
                topLevelRouteState = mutableStateOf(start),
                backStacks = mapOf(other to NavBackStack(other)),
            )
        }
    }

    @Test
    fun `topLevelRouteState must initialise to startRoute`() {
        assertFailsWith<IllegalArgumentException> {
            Nav3State(
                startRoute = start,
                topLevelRouteState = mutableStateOf(other),
                backStacks = mapOf(start to NavBackStack(start), other to NavBackStack(other)),
            )
        }
    }

    // ── Tab switching ──────────────────────────────────────────────────────

    @Test
    fun `tapping a different tab switches to it and records the previous tab`() {
        val s = state(other)

        s.onTabTapped(other)

        assertEquals(other, s.topLevelRoute)
        assertEquals(start, s.previousTopLevelRoute)
    }

    @Test
    fun `requireBackStackFor throws for an unknown route`() {
        val s = state(other)
        val unknown = AppDestination.CalendarGraph()

        assertFailsWith<IllegalStateException> { s.requireBackStackFor(unknown) }
    }

    // ── Reselect ───────────────────────────────────────────────────────────

    @Test
    fun `tapping the active tab does not change the route`() = runTest {
        val s = state(other)
        s.onTabTapped(other)

        s.onTabTapped(other)

        assertEquals(other, s.topLevelRoute)
    }

    @Test
    fun `tapping the active tab emits a reselect event`() = runTest {
        val s = state(other)
        s.onTabTapped(other)

        // The flow has no replay: in production the screen's LaunchedEffect is already
        // collecting when the user taps, so the collector must start BEFORE the tap.
        val received = async { s.reselectEvents.first() }
        runCurrent()

        s.onTabTapped(other)

        assertEquals(other, received.await())
    }

    @Test
    fun `tapping a different tab emits no reselect event`() = runTest {
        val s = state(other)

        val received = async { s.reselectEvents.first() }
        runCurrent()

        // Switching tabs is not a reselect — nothing should arrive, so `first()` stays
        // suspended. Assert the route moved, then cancel the pending collector.
        s.onTabTapped(other)

        assertEquals(other, s.topLevelRoute)
        assertEquals(start, s.previousTopLevelRoute)
        assertTrue(received.isActive, "a tab switch must not emit a reselect event")
        received.cancel()
    }
}
