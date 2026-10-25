package com.singularity.todo.feature.ai.external

import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.mcp.ToolServerCommand
import com.singularity.todo.feature.ai.acp.AcpAgentLaunch
import com.singularity.todo.feature.ai.acp.AcpAgentLauncher
import com.singularity.todo.feature.ai.acp.AcpAgentProfile
import com.singularity.todo.feature.ai.acp.AcpAgentSession
import com.singularity.todo.feature.ai.acp.AcpLifecycleSink
import com.singularity.todo.feature.ai.acp.AcpPermissionRequest
import com.singularity.todo.feature.ai.acp.AcpSessionPermissions
import com.singularity.todo.feature.ai.acp.LoggingAcpLifecycle
import com.singularity.todo.feature.ai.acp.PermissionDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/**
 * The conversation: the agent's process, the session on top of it, and the process
 * tree that ends both.
 *
 * ## Why it is its own thing
 *
 * Connecting is a sequence with a beginning and an end — spawn the process, hold the
 * session, shake hands, and either be connected or be finished — and it was written
 * in the middle of the screen's ViewModel, between the state updates that show it
 * happening. A reader following "what happens when the reader presses Connect" had
 * to read the turn's code and the permission's code to get there.
 *
 * ## The session is held before the handshake
 *
 * The agent's process exists from the moment it is launched, so the session is held
 * before the handshake and not after it. A connect that is cancelled during the
 * handshake — the reader navigated away — has to end the agent anyway: a third-party
 * process that outlives the screen keeps the user's credentials and the
 * application's data directory open, and nothing else would close it.
 */
internal class AcpAgentConversation(private val scope: CoroutineScope, private val launcher: AcpAgentLauncher) {

    private var session: AcpAgentSession? = null

    /** The open session, or null when there is none. */
    val openSession: AcpAgentSession? get() = session

    /**
     * Starts the conversation.
     *
     * @param toolServer the tool server the agent may reach, or null when there is
     *   none. The agent is connected either way: an agent without tool access can
     *   still talk — it is simply blind to the user's tasks.
     * @param askForPermission how the agent asks the reader for permission.
     * @return how it went.
     */
    suspend fun connect(
        agent: AcpAgentProfile,
        toolServer: ToolServerCommand.Available?,
        askForPermission: suspend (AcpPermissionRequest) -> PermissionDecision,
        /**
         * Where the session's lifecycle goes.
         *
         * One sink for the whole session: the client and the operations are two halves of
         * the same conversation, and their events belong in the same place.
         */
        sessionEvents: AcpLifecycleSink = LoggingAcpLifecycle(),
    ): AcpConversationOutcome {
        val permissions = AcpSessionPermissions { request -> askForPermission(request) }
        return when (
            val launch = launcher.launch(
                profile = agent,
                scope = scope,
                permissions = permissions,
                toolServer = toolServer,
                sessionEvents = sessionEvents,
            )
        ) {
            is AcpAgentLaunch.Failed -> AcpConversationOutcome.LaunchFailed(launch.reason)

            is AcpAgentLaunch.Started -> {
                session = launch.session
                openSession(agent, launch.session)
            }
        }
    }

    /**
     * Shakes hands, and says whether the agent answered.
     *
     * @return how the handshake went.
     */
    @Suppress("TooGenericExceptionCaught") // the agent can fail in many ways; the screen needs the message
    private suspend fun openSession(
        agent: AcpAgentProfile,
        agentSession: AcpAgentSession,
    ): AcpConversationOutcome {
        try {
            agentSession.connect()
        } catch (e: CancellationException) {
            // A cancellation that did not come from us is the connection failing, not the
            // screen going away. The client translates the protocol's own cancellation —
            // the one a dead agent raises — into a failure, so a cancellation that
            // reaches this point is the caller's, and it has to survive.
            //
            // The process was spawned before the handshake, so it exists whether or not
            // the handshake finished. A cancelled connect ends it: the screen is going
            // away and the agent goes with it, rather than being left running for a
            // conversation nobody is in.
            runCatchingCancellable { agentSession.terminate() }
            if (session === agentSession) session = null
            throw e
        } catch (e: Exception) {
            runCatchingCancellable { agentSession.close() }
            if (session === agentSession) session = null
            return AcpConversationOutcome.HandshakeFailed(e.message ?: "The agent did not answer.")
        }

        if (session !== agentSession) {
            // The session was closed while the handshake was in flight — the reader
            // pressed Disconnect while the agent was still answering. The agent is not
            // connected, and it is ended here rather than left running for a
            // conversation that was already closed. The screen has already said so.
            runCatchingCancellable { agentSession.close() }
            return AcpConversationOutcome.Closed
        }

        return AcpConversationOutcome.Connected(agent.displayName)
    }

    /**
     * Ends the conversation.
     *
     * The session is closed, not terminated: the agent is asked to end, and a
     * third-party process that ignores the request is ended by [terminate].
     */
    suspend fun close() {
        session?.let { open -> runCatchingCancellable { open.close() } }
        session = null
    }

    /**
     * Ends the agent's process, and everything it started.
     *
     * The process is ended through the session rather than through the scope: the
     * scope is closed first, so a coroutine launched there would never run.
     */
    fun terminate() {
        session?.terminate()
        session = null
    }
}

/**
 * How the conversation went.
 *
 * The three failures are kept apart because the screen says something different for
 * each: an agent that would not start and an agent that started but did not answer
 * are different problems, and a reader who is told only "it failed" has to guess
 * which one happened.
 */
internal sealed interface AcpConversationOutcome {

    /** The agent answered the handshake. */
    data class Connected(val agentName: String) : AcpConversationOutcome

    /** The agent's process could not be started. */
    data class LaunchFailed(val reason: String) : AcpConversationOutcome

    /** The agent started but did not answer the handshake. */
    data class HandshakeFailed(val reason: String) : AcpConversationOutcome

    /** The session was closed while the handshake was in flight. */
    data object Closed : AcpConversationOutcome
}
