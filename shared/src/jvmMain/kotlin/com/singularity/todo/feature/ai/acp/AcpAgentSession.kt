package com.singularity.todo.feature.ai.acp

import kotlinx.coroutines.flow.Flow

/**
 * A conversation with an agent, as the screen needs it.
 *
 * ## Why this is an interface
 *
 * The screen's ViewModel has to be testable without an agent installed on the machine
 * running the tests — and, more importantly, the situations that matter most (a
 * permission request that is never answered, a turn that fails halfway, a second
 * message sent while a turn runs) are the ones where no real agent is involved anyway.
 *
 * The interface is also what keeps the protocol library behind one door: everything
 * above it is written in the application's vocabulary.
 */
interface AcpAgentSession {

    /** What the agent says about itself, once the conversation is open. */
    suspend fun connect(): AcpAgentInfo

    /**
     * Sends a message and reports what the agent does.
     *
     * The flow ends when the turn ends. It does not end when the agent stops talking:
     * the completion event is what ends a turn, and its absence is a failure.
     */
    fun prompt(message: String): Flow<AcpTurnEvent>

    /** Asks the agent to stop the turn it is running. */
    suspend fun cancel()

    /** Ends the conversation, and the process behind it. */
    suspend fun close()

    /**
     * Ends the agent's process without waiting.
     *
     * For the case where there is no scope left to wait in — the screen is going away.
     * The graceful [close] is the path a disconnect takes.
     */
    fun terminate()
}

/**
 * What the agent says about itself.
 *
 * The protocol's own type is not carried: the screen shows a name and a version, and
 * nothing above this line needs the rest of what the protocol says.
 *
 * [protocolVersion] is the exception, and it is the one thing about the agent that has
 * to be compared rather than shown: it says which version of the protocol the agent
 * speaks, and a client that speaks another one cannot rely on what the agent means.
 */
data class AcpAgentInfo(
    val name: String,
    val version: String,
    /** The version of the protocol the agent speaks. */
    val protocolVersion: Int,
)
