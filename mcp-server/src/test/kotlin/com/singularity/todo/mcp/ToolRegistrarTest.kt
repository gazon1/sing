package com.singularity.todo.mcp

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.Tool
import ai.koog.serialization.TypeToken
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The kill switch, at the place where it is applied.
 *
 * The in-app agent has honoured these flags since the flags existed; the MCP server did
 * not, so the same switch meant two different things depending on who was asking. The
 * rule now lives in one place — [com.singularity.todo.core.ai.filterTools] — and the
 * server applies it *before* registering, so a disabled tool is not in the list at all.
 *
 * That last part is the property worth testing: a tool that is filtered out but still
 * registered would still cost the agent its schema, and it would still be callable.
 */
@Tag("fast")
class ToolRegistrarTest {

    private class EnabledTool :
        SimpleTool<Unit>(TypeToken.of(Unit::class.java), NAME, DESCRIPTION) {
        override suspend fun execute(args: Unit): String = "{}"

        companion object {
            const val NAME = "enabled_tool"
            const val DESCRIPTION = "A tool that is switched on."
        }
    }

    private class DisabledTool :
        SimpleTool<Unit>(TypeToken.of(Unit::class.java), NAME, DESCRIPTION) {
        override suspend fun execute(args: Unit): String = "{}"

        companion object {
            const val NAME = "disabled_tool"
            const val DESCRIPTION = "A tool that is switched off."
        }
    }

    /**
     * The tool list is passed in, so this test needs no container at all.
     *
     * An earlier version started and stopped the *global* Koin context per test, which
     * is a race: the JVM runs these tests in parallel, and one test's `stopKoin()`
     * pulled the graph out from under another test's registrar. A registrar that
     * resolves its own dependencies cannot be tested without that kind of shared state.
     */
    private fun tools(): List<Tool<*, *>> = listOf(EnabledTool(), DisabledTool())

    private fun server(): Server = Server(
        serverInfo = Implementation(
            name = "singularity-todo",
            version = "0.0.0-test",
            title = null,
            websiteUrl = null,
            icons = emptyList(),
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
        ),
    )

    @Test
    fun `a disabled tool is never registered`() {
        val server = server()

        ToolRegistrar(server, tools(), toolFlags = mapOf(DisabledTool.NAME to false)).registerAll()

        assertTrue(EnabledTool.NAME in server.tools, "the enabled tool must be registered")
        assertFalse(
            DisabledTool.NAME in server.tools,
            "a disabled tool must not be registered: it would still cost the agent its " +
                "schema, and it would still be callable",
        )
    }

    @Test
    fun `a tool with no flag is enabled`() {
        val registrar = ToolRegistrar(server(), tools())

        assertEquals(2, registrar.snapshot.registered.size)
        assertEquals(0, registrar.snapshot.disabled.size)
    }

    @Test
    fun `the snapshot names what was disabled`() {
        val registrar = ToolRegistrar(server(), tools(), toolFlags = mapOf(DisabledTool.NAME to false))

        assertEquals(listOf(EnabledTool.NAME), registrar.snapshot.registered.map { it.name })
        assertEquals(listOf(DisabledTool.NAME), registrar.snapshot.disabled.map { it.name })
    }

    @Test
    fun `the snapshot reports the configuration it applied`() {
        val registrar = ToolRegistrar(server(), tools(), toolFlags = mapOf(DisabledTool.NAME to false))

        assertEquals(
            ConfigurationStatus.UNAVAILABLE,
            registrar.snapshot.configurationStatus,
            "a registrar built without a configuration must say so rather than look current",
        )
    }
}
