package com.singularity.todo.feature.nav

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * REQ-DN-004: when the shell has no entry to render, the line it records has to say what
 * the navigation state held — not only which route was active. The route alone cannot tell
 * "the active stack lost its key" from "the other stack did", and that distinction is the
 * whole reason the blank screen was undiagnosable.
 */
@Tag("fast")
class BlankTreeDiagnosticTest {
    private val today: NavKey = AppDestination.AgendaGraph(AgendaStartRoute.Today)
    private val upcoming: NavKey = AppDestination.AgendaGraph(AgendaStartRoute.Upcoming)

    private fun state(active: NavKey = today): Nav3State =
        Nav3State(
            startRoute = today,
            topLevelRouteState = mutableStateOf(today),
            backStacks =
                mapOf(
                    today to NavBackStack(today).apply { add(AppDestination.Settings) },
                    upcoming to NavBackStack(upcoming).apply { clear() },
                ),
        ).apply { topLevelRoute = active }

    @Test
    fun `the report names the active route, the tab behind it and every stack with its keys`() {
        val line = blankTreeReport(state(active = upcoming))

        assertTrue(line.startsWith("WARNING [blank-screen]"), line)
        assertTrue(line.contains("active=$upcoming"), line)
        assertTrue(line.contains("previous=$today"), line)
        // The stack that still holds keys is listed with them, in order...
        assertTrue(line.contains("$today -> [$today, ${AppDestination.Settings}]"), line)
        // ...and the empty one is visible as empty, which is the evidence a capture needs.
        assertTrue(line.contains("$upcoming -> []"), line)
    }

    @Test
    fun `recording a report sends exactly one line through the given sink`() {
        val lines = mutableListOf<String>()

        logBlankTree(blankTreeReport(state())) { lines += it }

        assertEquals(1, lines.size)
        assertEquals(blankTreeReport(state()), lines.single())
    }
}
