package com.singularity.todo.feature.ai.acp

import co.touchlab.kermit.Logger

/**
 * What happened to a session, in the application's own words.
 *
 * ## Why these five
 *
 * The questions a person asks about a conversation with a third-party agent are: did it
 * connect, what was it, what did it ask for, and is it gone. Each of them is a fact about
 * the session's *lifecycle*, and each of them is something the log can answer and the
 * screen does not: the screen shows the conversation, and the log says what the
 * conversation was.
 *
 * Without them, the log holds four debug lines about a handshake that went wrong, and
 * nothing at all about the sessions that went right — and a session that went wrong is
 * the one nobody can reconstruct afterwards.
 */
sealed interface AcpSessionEvent {

    /** The agent answered the handshake, and the session is open. */
    data class Connected(val agentName: String, val agentVersion: String, val protocolVersion: Int) :
        AcpSessionEvent

    /** The session exists, and this is which one. */
    data class SessionOpened(val sessionId: String) : AcpSessionEvent

    /** The agent asked the reader for permission. */
    data class PermissionRequested(val toolCallId: String, val title: String) : AcpSessionEvent

    /** The conversation is over. */
    data class Disconnected(val reason: String) : AcpSessionEvent

    /** The agent's process tree is gone — how many processes it took. */
    data class ProcessTreeTerminated(val processes: Int) : AcpSessionEvent
}

/** How an event reads in one line. */
fun AcpSessionEvent.describe(): String = when (this) {
    is AcpSessionEvent.Connected ->
        "Connected to $agentName $agentVersion (protocol version $protocolVersion)"

    is AcpSessionEvent.SessionOpened -> "Session $sessionId opened"

    is AcpSessionEvent.PermissionRequested -> "Permission requested for '$title' ($toolCallId)"

    is AcpSessionEvent.Disconnected -> "Disconnected: $reason"

    is AcpSessionEvent.ProcessTreeTerminated -> "Process tree terminated: $processes process(es)"
}

/**
 * Where a session's lifecycle goes.
 *
 * A port rather than a direct call to the logger, so that a test can see the events
 * without reading the log — and so that the screen may subscribe to the same events
 * later without the client having to know.
 */
fun interface AcpLifecycleSink {
    /** Records one event. */
    fun record(event: AcpSessionEvent)
}

/**
 * The sink that writes the lifecycle to the log.
 *
 * The log is where these belong: they are the facts about a session that nobody is
 * watching, and a reader who is watching can see the conversation itself.
 */
class LoggingAcpLifecycle(private val logger: Logger = Logger.withTag("acp-session")) : AcpLifecycleSink {
    override fun record(event: AcpSessionEvent) {
        logger.i { event.describe() }
    }
}
