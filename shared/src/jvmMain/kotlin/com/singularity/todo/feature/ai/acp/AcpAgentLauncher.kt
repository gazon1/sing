package com.singularity.todo.feature.ai.acp

import com.singularity.todo.core.mcp.ToolServerCommand
import com.singularity.todo.core.process.ProcessTree
import com.singularity.todo.core.process.Subprocess
import java.io.IOException
import kotlinx.coroutines.CoroutineScope

/**
 * Starts an external agent and opens a session on it.
 *
 * ## A port, so that "the agent is not installed" is a value
 *
 * The ViewModel that owns the conversation has to be testable without a Gemini CLI
 * installed on the machine running the tests, and — more importantly — the failure of
 * an agent that is not installed has to be a *value* the code can carry, not only
 * something that can happen to a process.
 */
fun interface AcpAgentLauncher {

    /**
     * Starts the agent and opens a session on it.
     *
     * @param scope the scope the session lives in.
     * @param operations what the agent may ask of the application.
     * @param toolServer the tool server the agent may use, or null when the agent is to
     *   connected without tool access.
     */
    fun launch(
        profile: AcpAgentProfile,
        scope: CoroutineScope,
        operations: AcpClientSessionOperations,
        toolServer: ToolServerCommand.Available?,
    ): AcpAgentLaunch
}

/**
 * The result of starting an agent.
 */
sealed interface AcpAgentLaunch {

    /** The agent is running and the session is open. */
    data class Started(val session: AcpAgentSession) : AcpAgentLaunch

    /**
     * The agent did not start.
     *
     * @param reason what a person can be told. It names the command, because the usual
     *   cause is that the command is not on the machine's path.
     */
    data class Failed(val reason: String) : AcpAgentLaunch
}

/**
 * The launcher that starts a real process.
 *
 * ## What the child's standard error is for
 *
 * The protocol speaks over standard output, so standard error is free to carry whatever
 * the agent wants to say — and what an agent says when it cannot start is the most
 * useful thing there is. "command not found", "unknown flag", "node: not installed" all
 * arrive there, and all of them are answers to a question the user is about to ask.
 *
 * So the last few lines are kept and quoted in the failure. A failure that says only
 * "could not start" sends the user to search; a failure that says "sh: gemini: command
 * not found" ends the search.
 */
object SubprocessAcpAgentLauncher : AcpAgentLauncher {

    private const val DIAGNOSTIC_LINES = 20

    override fun launch(
        profile: AcpAgentProfile,
        scope: CoroutineScope,
        operations: AcpClientSessionOperations,
        toolServer: ToolServerCommand.Available?,
    ): AcpAgentLaunch {
        val diagnostics = ArrayDeque<String>()
        val argv = listOf(profile.command) + profile.args
        return try {
            val process = Subprocess.spawnDuplex(
                argv = argv,
                stderrSink = { line ->
                    if (diagnostics.size >= DIAGNOSTIC_LINES) diagnostics.removeFirst()
                    diagnostics.addLast(line)
                },
            )
            val tree = ProcessTree(process.process.toHandle())
            AcpAgentLaunch.Started(
                AcpClient(
                    scope = scope,
                    process = process,
                    tree = tree,
                    operations = operations,
                    toolServer = toolServer,
                ),
            )
        } catch (e: IOException) {
            AcpAgentLaunch.Failed(failureReason(profile, diagnostics, e))
        } catch (e: SecurityException) {
            AcpAgentLaunch.Failed(failureReason(profile, diagnostics, e))
        }
    }

    private fun failureReason(
        profile: AcpAgentProfile,
        diagnostics: ArrayDeque<String>,
        error: Throwable,
    ): String {
        val quoted = diagnostics.joinToString("; ").takeIf { it.isNotBlank() }
        val detail = quoted ?: error.message ?: error::class.simpleName.orEmpty()
        return "Could not start ${profile.displayName} (${profile.commandLine()}): $detail"
    }
}

/**
 * A launcher that starts nothing.
 *
 * Used where no process may be started — the ViewModel's own tests, and any caller
 * that only wants the failure path.
 */
object FailingAcpAgentLauncher : AcpAgentLauncher {
    override fun launch(
        profile: AcpAgentProfile,
        scope: CoroutineScope,
        operations: AcpClientSessionOperations,
        toolServer: ToolServerCommand.Available?,
    ): AcpAgentLaunch = AcpAgentLaunch.Failed("No agent could be started.")
}
