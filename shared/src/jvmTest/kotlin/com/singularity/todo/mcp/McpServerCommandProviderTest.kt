package com.singularity.todo.mcp

import com.singularity.todo.core.mcp.McpServerCommandProvider
import com.singularity.todo.core.mcp.ToolServerCommand
import com.singularity.todo.core.mcp.ToolServerEnvironment
import com.singularity.todo.feature.profile.ProfileId
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tool-server command the external agent is handed carries the screen-inspection
 * opt-in, and only when the caller opted in.
 */
@Tag("fast")
class McpServerCommandProviderTest {
    @Test
    fun `the packaged tool server command opts into the inspector host through the opt-in`() {
        val provider =
            McpServerCommandProvider(
                ToolServerEnvironment(
                    packaged = true,
                    launcher = executableJava(),
                    javaBinary = null,
                    classpath = "",
                ),
            )

        val optedOut =
            provider.commandFor(ProfileId("abc-123")) as ToolServerCommand.Available
        assertEquals(executableJava(), optedOut.command)
        assertEquals(
            listOf(McpServerCommandProvider.TOOL_SERVER_FLAG, "--profile-id=abc-123"),
            optedOut.args,
        )

        val optedIn =
            provider.commandFor(ProfileId("abc-123"), inspectorOptIn = true) as ToolServerCommand.Available
        assertEquals(executableJava(), optedIn.command)
        assertEquals(
            listOf(
                McpServerCommandProvider.TOOL_SERVER_FLAG,
                McpServerCommandProvider.INSPECTOR_HOST_FLAG,
                "--profile-id=abc-123",
            ),
            optedIn.args,
        )
    }

    @Test
    fun `the development tool server command opts into the inspector host through the opt-in`() {
        val provider =
            McpServerCommandProvider(
                ToolServerEnvironment(
                    packaged = false,
                    launcher = null,
                    javaBinary = executableJava(),
                    classpath = "com/singularity/todo/MainKt",
                ),
            )

        val optedOut =
            provider.commandFor(ProfileId("abc-123")) as ToolServerCommand.Available
        assertEquals(
            listOf(
                "-cp",
                "com/singularity/todo/MainKt",
                "com.singularity.todo.MainKt",
                McpServerCommandProvider.TOOL_SERVER_FLAG,
                "--profile-id=abc-123",
            ),
            optedOut.args,
        )

        val optedIn =
            provider.commandFor(ProfileId("abc-123"), inspectorOptIn = true) as ToolServerCommand.Available
        assertEquals(
            listOf(
                "-Dsingularity.debug=true",
                "-cp",
                "com/singularity/todo/MainKt",
                "com.singularity.todo.MainKt",
                McpServerCommandProvider.TOOL_SERVER_FLAG,
                McpServerCommandProvider.INSPECTOR_HOST_FLAG,
                "--profile-id=abc-123",
            ),
            optedIn.args,
        )
    }

    private fun executableJava(): String = File(System.getProperty("java.home"), "bin/java").absolutePath
}
