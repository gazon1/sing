package com.singularity.todo.mcp

import com.singularity.todo.core.mcp.McpServerCommandProvider
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The local screen-inspection host is opt-in, and the opt-in is the whole decision.
 *
 * The host starts only through the explicit flag, and the start-up resolves the flag
 * from the process arguments the same way it resolves the profile selection. Without
 * the flag the start-up resolves no host, in any build, and a release build is
 * therefore no different from a debug one until the opt-in is made.
 */
@Tag("fast")
class InspectorHostOptInTest {
    @Test
    fun `the inspector host opts in only through the explicit flag`() {
        assertTrue(inspectorHostOptIn(arrayOf(McpServerCommandProvider.INSPECTOR_HOST_FLAG)))
        assertTrue(
            inspectorHostOptIn(
                arrayOf("--mcp-server", McpServerCommandProvider.INSPECTOR_HOST_FLAG, "--profile-id=abc-123"),
            ),
        )
        assertFalse(inspectorHostOptIn(arrayOf("--mcp-server")))
        assertFalse(inspectorHostOptIn(arrayOf("--mcp-server", "--profile-id=abc-123")))
    }

    @Test
    fun `the inspector host is off without the opt-in`() {
        val context = ToolServerStartupContext(ProfileSelection.Stored, toolServerKoinStartup)

        assertFalse(context.inspectorHostEnabled)
    }

    @Test
    fun `the inspector host is on through the opt-in`() {
        val context =
            ToolServerStartupContext(
                selection = ProfileSelection.Stored,
                koinStartup = toolServerKoinStartup,
                inspectorHostOptIn = true,
            )

        assertTrue(context.inspectorHostEnabled)
    }

    private val toolServerKoinStartup =
        object : ToolServerKoinStartup {
            override fun start(fixedProfileId: String?) = Unit
        }
}
