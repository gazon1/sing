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
 * the flag the start-up resolves no host, in any build, and a release build cannot be
 * opted in at all.
 */
@Tag("fast")
class InspectorHostOptInTest {
    @Test
    fun `the inspector host opts in only through the explicit flag`() {
        assertTrue(inspectorHostOptIn(arrayOf(McpServerCommandProvider.INSPECTOR_HOST_FLAG), isDebugBuild = true))
        assertTrue(
            inspectorHostOptIn(
                arrayOf("--mcp-server", McpServerCommandProvider.INSPECTOR_HOST_FLAG, "--profile-id=abc-123"),
                isDebugBuild = true,
            ),
        )
        assertFalse(inspectorHostOptIn(arrayOf("--mcp-server"), isDebugBuild = true))
        assertFalse(inspectorHostOptIn(arrayOf("--mcp-server", "--profile-id=abc-123"), isDebugBuild = true))
    }

    @Test
    fun `the inspector host cannot opt in through the flag in a release build`() {
        assertFalse(inspectorHostOptIn(arrayOf(McpServerCommandProvider.INSPECTOR_HOST_FLAG), isDebugBuild = false))
        assertFalse(
            inspectorHostOptIn(
                arrayOf("--mcp-server", McpServerCommandProvider.INSPECTOR_HOST_FLAG, "--profile-id=abc-123"),
                isDebugBuild = false,
            ),
        )
    }

    @Test
    fun `the standalone server cannot opt in through the flag in a release build`() {
        // The standalone entry point applies the same debug gate as the desktop one; a
        // release launch hands the inspector host none even when the flag is supplied.
        assertFalse(inspectorHostOptIn(arrayOf(McpServerCommandProvider.INSPECTOR_HOST_FLAG), isDebugBuild = false))
        assertFalse(
            inspectorHostOptIn(
                arrayOf("--mcp-server", McpServerCommandProvider.INSPECTOR_HOST_FLAG, "--profile-id=abc-123"),
                isDebugBuild = false,
            ),
        )
    }

    @Test
    fun `a release build cannot opt in through the flag even when the debug property is injected`() {
        // The release gate is the build's decision; even a debug property injected through
        // the JVM and the explicit opt-in flag cannot turn a release build's host on.
        System.setProperty("singularity.debug", "true")
        try {
            assertFalse(inspectorHostOptIn(arrayOf(McpServerCommandProvider.INSPECTOR_HOST_FLAG), isDebugBuild = false))
            assertFalse(
                inspectorHostOptIn(
                    arrayOf("--mcp-server", McpServerCommandProvider.INSPECTOR_HOST_FLAG, "--profile-id=abc-123"),
                    isDebugBuild = false,
                ),
            )
        } finally {
            System.clearProperty("singularity.debug")
        }
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
