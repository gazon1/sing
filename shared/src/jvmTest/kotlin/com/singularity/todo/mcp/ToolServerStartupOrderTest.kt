package com.singularity.todo.mcp

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The start-up's order, and the names of the steps in it.
 *
 * ## What this protects
 *
 * The order is a contract with three things: the shutdown hook, which closes the
 * server and so has to be installed after the server exists; the refusal that needs
 * nothing but the arguments, which has to happen before the container is started,
 * because a refusal after it leaves a half-started process behind; and the client,
 * which is told one thing while the process leaves with another.
 *
 * Only the sequence of statements used to know that order. Now it is a list, and this
 * is what says the list did not change by accident.
 */
@Tag("fast")
class ToolServerStartupOrderTest {

    @Test
    fun `the steps run in the order they are written down`() {
        assertEquals(
            listOf(
                "refuse an ambiguous request",
                "warn about a deprecated argument",
                "build the graph",
                "resolve the dependencies",
                "verify the database",
                "refuse a profile that does not exist",
                "bootstrap the profiles",
                "read the configuration",
                "build the server",
                "install the shutdown hook",
            ),
            ToolServerStartupOrder.steps.map { it.title },
        )
    }

    @Test
    fun `every step has a name of its own`() {
        val titles = ToolServerStartupOrder.steps.map { it.title }

        assertEquals(titles.size, titles.toSet().size, "No two steps share a name")
        assertTrue(titles.none { it.isBlank() }, "Every step is named")
    }

    @Test
    fun `the server is built before the hook that closes it`() {
        val titles = ToolServerStartupOrder.steps.map { it.title }

        assertTrue(
            titles.indexOf("build the server") < titles.indexOf("install the shutdown hook"),
            "The hook closes the server, so it comes after it",
        )
    }

    @Test
    fun `the refusal that needs nothing but the arguments comes before the graph`() {
        val titles = ToolServerStartupOrder.steps.map { it.title }

        assertTrue(
            titles.indexOf("refuse an ambiguous request") < titles.indexOf("build the graph"),
            "A refusal after the graph is built leaves a half-started process behind",
        )
    }

    @Test
    fun `the order builds the server`() {
        // The runner's own check — that the server exists when the sequence ends —
        // is backed by this: the step that builds it is in the order, and the order is
        // written down in one place.
        assertTrue(
            ToolServerStartupOrder.steps.any { it === BuildTheServer },
            "The order builds the server",
        )
    }
}
