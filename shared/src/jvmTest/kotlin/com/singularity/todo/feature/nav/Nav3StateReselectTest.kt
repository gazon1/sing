@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.nav

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
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
@Tag("fast")
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

    // ── Navigator.open — the policy facade (REQ-NAV-001) ─────────────────
    //
    // `plans` is a real bottom-bar tab, so the policy classifies it as top-level;
    // the synthetic `other` (NotesGraph) above is not.

    @Test
    fun `open of a top-level destination switches tabs and records the previous one`() {
        val s = state(AppDestination.Plans)
        val navigator = Navigator(s)

        navigator.open(AppDestination.Plans)

        assertEquals(AppDestination.Plans, s.topLevelRoute)
        assertEquals(start, s.previousTopLevelRoute)
    }

    @Test
    fun `open of a sub-route pushes onto the current stack without switching tabs`() {
        val s = state(AppDestination.Plans)
        val navigator = Navigator(s)
        val target = AppDestination.ProjectDetail("p1")

        navigator.open(target)

        val stack = s.requireBackStackFor(s.topLevelRoute)
        assertEquals(target, stack.lastOrNull())
        assertEquals(2, stack.size)
        assertEquals(start, s.topLevelRoute, "a cross-feature open must keep the origin stack active")
    }

    @Test
    fun `reopening the active tab through open emits a reselect and touches no stack`() = runTest {
        val s = state(AppDestination.Plans)
        val navigator = Navigator(s)
        navigator.open(AppDestination.Plans)
        val stackSize = s.requireBackStackFor(s.topLevelRoute).size

        val received = async { s.reselectEvents.first() }
        runCurrent()
        navigator.open(AppDestination.Plans)

        assertEquals(AppDestination.Plans, received.await())
        assertEquals(stackSize, s.requireBackStackFor(s.topLevelRoute).size)
    }

    @Test
    fun `open of a bare nested start route fails before touching the stack`() {
        val s = state(AppDestination.Plans)
        val navigator = Navigator(s)
        val before = s.requireBackStackFor(s.topLevelRoute).toList()

        val error = assertFailsWith<IllegalArgumentException> {
            navigator.open(AppDestination.TasksStartRoute.Create())
        }

        assertTrue(
            error.message.orEmpty().contains("$start"),
            "error must name the source context: ${error.message}",
        )
        assertEquals(before, s.requireBackStackFor(s.topLevelRoute).toList())
    }
}
