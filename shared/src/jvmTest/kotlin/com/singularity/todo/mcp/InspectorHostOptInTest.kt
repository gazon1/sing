package com.singularity.todo.mcp

import com.singularity.todo.core.mcp.McpServerCommandProvider
import com.singularity.todo.mcp.introspection.ScreenInspectorCli
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The local screen-inspection host is opt-in, and the opt-in is the whole decision.
 *
 * Two independent things have to agree, and neither defaults on: the build compiled the
 * capability in, and this process passed the explicit flag. Without the flag no host starts,
 * in any build; a release build cannot be opted in at all.
 */
@Tag("fast")
class InspectorHostOptInTest {
    @Test
    fun `the inspector host opts in only through the explicit flag`() {
        assertTrue(
            inspectorHostOptIn(
                arrayOf(McpServerCommandProvider.INSPECTOR_HOST_FLAG),
                inspectorHostCompiledIn = true,
            ),
        )
        assertTrue(
            inspectorHostOptIn(
                arrayOf("--mcp-server", McpServerCommandProvider.INSPECTOR_HOST_FLAG, "--profile-id=abc-123"),
                inspectorHostCompiledIn = true,
            ),
        )
        assertFalse(inspectorHostOptIn(arrayOf("--mcp-server"), inspectorHostCompiledIn = true))
        assertFalse(
            inspectorHostOptIn(
                arrayOf("--mcp-server", "--profile-id=abc-123"),
                inspectorHostCompiledIn = true,
            ),
        )
    }

    @Test
    fun `a release build cannot opt in even when the flag and the debug property are present`() {
        // The release gate is the build's decision. A JVM property is not a build input, so
        // neither it nor the explicit flag can turn a release build's host on.
        System.setProperty("singularity.debug", "true")
        try {
            assertFalse(
                inspectorHostOptIn(
                    arrayOf(McpServerCommandProvider.INSPECTOR_HOST_FLAG),
                    inspectorHostCompiledIn = false,
                ),
            )
            assertFalse(
                inspectorHostOptIn(
                    arrayOf("--mcp-server", McpServerCommandProvider.INSPECTOR_HOST_FLAG, "--profile-id=abc-123"),
                    inspectorHostCompiledIn = false,
                ),
            )
        } finally {
            System.clearProperty("singularity.debug")
        }
    }

    @Test
    fun `the start-up context is off without the opt-in`() {
        val context = ToolServerStartupContext(ProfileSelection.Stored, toolServerKoinStartup)

        assertFalse(context.inspectorHostEnabled)
    }

    @Test
    fun `the start-up context is on through the opt-in`() {
        val context =
            ToolServerStartupContext(
                selection = ProfileSelection.Stored,
                koinStartup = toolServerKoinStartup,
                inspectorHostEnabled = true,
            )

        assertTrue(context.inspectorHostEnabled)
    }

    @Test
    fun `no screen inspector tool is registered without the opt-in`() {
        val server = server()

        registerScreenInspectorHost(server, inspectorHostEnabled = false)

        assertFalse(
            server.tools.keys.any { it.startsWith("screen_") },
            "a host that was not opted in must register no screen_* tool: ${server.tools.keys}",
        )
    }

    @Test
    fun `the screen inspector tools are registered with the opt-in`() {
        val server = server()

        registerScreenInspectorHost(server, inspectorHostEnabled = true)

        assertTrue(
            "screen_list" in server.tools,
            "the opted-in host must register its tools, got ${server.tools.keys}",
        )
    }

    @Test
    fun `the agent CLI refuses to run when the host is not compiled in`() {
        val code =
            ScreenInspectorCli.run(
                listOf("screens"),
                defaultKoinStartup = null,
                inspectorHostCompiledIn = false,
            )

        assertEquals(2, code)
    }

    @Test
    fun `the agent CLI answers help when the host is compiled in`() {
        val code =
            ScreenInspectorCli.run(
                listOf("help"),
                defaultKoinStartup = null,
                inspectorHostCompiledIn = true,
            )

        assertEquals(0, code)
    }

    private fun server(): Server =
        Server(
            serverInfo =
                Implementation(
                    name = "singularity-todo",
                    version = "0.0.0-test",
                    title = null,
                    websiteUrl = null,
                    icons = emptyList(),
                ),
            options =
                ServerOptions(
                    capabilities =
                        ServerCapabilities(
                            tools = ServerCapabilities.Tools(listChanged = true),
                        ),
                ),
        )

    private val toolServerKoinStartup =
        object : ToolServerKoinStartup {
            override fun start(fixedProfileId: String?) = Unit
        }
}
