package com.singularity.todo.shell

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.Nav3State
import com.singularity.todo.feature.nav.Navigator
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Desktop back policy (#1523): pop when there is something to pop, close the window
 * when there is not.
 *
 * This is the Desktop half of the pairing the Android shell bar already makes between
 * `Navigator.goBack()`'s result and `activity.finish()`. The shell cannot be asked for
 * this at the ultimate root through a mounted screen — nothing renders a back control
 * when there is nothing to go back to — so the policy is driven directly here, with the
 * same plain `NavBackStack` setup the `Nav3State` tests use.
 */
@Tag("fast")
class DesktopBackTest {
    private val start: NavKey = AppDestination.AgendaGraph(AgendaStartRoute.Today)

    private fun state(vararg routes: NavKey): Nav3State {
        val all = listOf(start) + routes
        return Nav3State(
            startRoute = start,
            topLevelRouteState = mutableStateOf(start),
            backStacks = all.associateWith { NavBackStack(it) },
        )
    }

    @Test
    fun `a back with nothing left to pop asks the host to close the window`() {
        var closes = 0

        desktopBack(Navigator(state()), onCloseRequest = { closes++ })

        assertEquals(1, closes, "the ultimate root is where Android finishes the activity")
    }

    @Test
    fun `a back that pops a nested screen closes nothing`() {
        val state = state()
        val nested: NavKey = AppDestination.Settings
        state.requireBackStackFor(start).add(nested)
        var closes = 0

        desktopBack(Navigator(state), onCloseRequest = { closes++ })

        assertEquals(0, closes, "a handled back must not also take the window with it")
        assertEquals(
            listOf(start),
            state.requireBackStackFor(start).toList(),
            "the nested screen is the one that left",
        )
    }
}
