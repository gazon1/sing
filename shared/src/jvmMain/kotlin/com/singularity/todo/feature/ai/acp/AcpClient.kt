@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.singularity.todo.feature.ai.acp

import co.touchlab.kermit.Logger
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.McpServer
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.llm.AgentEvent
import com.singularity.todo.core.llm.AgentTurnOutcome
import com.singularity.todo.core.mcp.ToolServerCommand
import com.singularity.todo.core.process.DuplexProcess
import com.singularity.todo.core.process.ProcessTree
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What happened in a turn, as the application understands it.
 *
 * Two kinds, and the second is not optional: a turn that ends without one has not
 * finished. The absence of [Finished] is what a failure *is* — a provider that died
 * mid-turn ends the stream, and a reader told "the agent finished" would be told a lie.
 */
sealed interface AcpTurnEvent {

    /** Something the agent did. */
    data class Activity(val event: AgentEvent) : AcpTurnEvent

    /** The turn ended, and this is how. */
    data class Finished(val outcome: AgentTurnOutcome) : AcpTurnEvent
}

/**
 * The application, speaking the Agent Client Protocol to an external agent.
 *
 * ## This is the only place the protocol library is driven
 *
 * Everything else in this feature speaks in the application's own vocabulary: the
 * adapter maps the protocol's updates onto activity, and the screen shows activity. So
 * the library is reached in one place, and what it does there can be read in one sitting.
 *
 * ## What the application advertises
 *
 * Neither the file system nor the terminal. The agent reaches the user's tasks through
 * the application's own tool server, whose writes go through the proposal pipeline —
 * where a write becomes a question for the user. An agent with file and terminal access
 * would have a second, quieter way to change the user's data, and it would not ask.
 *
 * ## The turn ends when the protocol says so
 *
 * A turn is finished when the agent sends its completion event, and not when the stream
 * ends. The stream also ends when the agent's process dies, when the transport breaks,
 * and when the reader cancels — and none of those is a completion. The mapping from the
 * protocol's stop reasons to outcomes is [AcpEventAdapter]'s; this class only carries it.
 *
 * ## What this class does not do
 *
 * It does not own the process. The process tree belongs to whoever started the
 * conversation — the screen's ViewModel — because closing a session has to end the
 * agent *and* everything it started, and that is one decision made in one place.
 */
class AcpClient(
    private val scope: CoroutineScope,
    private val process: DuplexProcess,
    private val tree: ProcessTree,
    private val operations: AcpClientSessionOperations,
    private val toolServer: ToolServerCommand.Available?,
    private val workingDirectory: String = System.getProperty("user.home").orEmpty(),
    /**
     * How long the agent has to answer the handshake.
     *
     * A parameter rather than a constant, because the deadline is measured on the
     * caller's clock and a test that runs on a virtual clock would see it fire before
     * the agent had a chance to start. A test that wants the deadline to be short says
     * so.
     */
    private val connectTimeout: Duration = CONNECT_TIMEOUT,
    /**
     * How long the agent has to answer the goodbye.
     *
     * A parameter for the same reason as [connectTimeout]: the deadline is measured on
     * the caller's clock, and a test that wants it short says so.
     */
    private val closeTimeout: Duration = CLOSE_TIMEOUT,
) : AcpAgentSession {

    private val logger = Logger.withTag("acp-client")

    private var transport: StdioTransport? = null
    private var protocol: Protocol? = null
    private var client: Client? = null
    private var session: com.agentclientprotocol.client.ClientSession? = null

    /**
     * Starts the conversation with the agent.
     *
     * @return what the agent says about itself.
     * @throws Exception when the agent cannot be reached, does not speak the protocol,
     *   or answers with a version this client does not speak. The message says which.
     */
    @Suppress("NoSwallowedCancellation") // the protocol's cancellation is not the caller's
    override suspend fun connect(): AcpAgentInfo {
        val ioTransport = StdioTransport(
            parentScope = scope,
            ioDispatcher = Dispatchers.IO,
            input = process.stdoutLines(),
            output = { line -> process.writeLine(line) },
            name = "acp-stdio",
        )
        transport = ioTransport

        val ioProtocol = Protocol(scope, ioTransport)
        protocol = ioProtocol
        ioProtocol.start()

        val ioClient = Client(ioProtocol)
        client = ioClient

        // The handshake is bounded. An agent that never answers — because it is broken,
        // or because it is a bridge that failed to start — must not leave the screen
        // showing a spinner for ever, and the screen offers no way out of that state
        // while it is connecting. The bound is generous because the agent may be an
        // `npx` invocation downloading its package on first use, and a timeout that
        // fired during a download would be a timeout that fired for the wrong reason.
        val handshake = try {
            withTimeoutOrNull(connectTimeout) {
                val info = ioClient.initialize(
                    ClientInfo(
                        capabilities = ClientCapabilities(),
                        implementation = Implementation(
                            name = "singularity-todo",
                            version = appVersionCode(),
                            title = "Singularity Todo",
                        ),
                    ),
                )
                val ioSession = ioClient.newSession(
                    SessionCreationParameters(
                        cwd = workingDirectory,
                        // An empty list means "no tool access" — the agent is connected,
                        // and it is blind to the user's tasks. That is a state the screen
                        // has to be able to show, so it is expressed here rather than faked.
                        mcpServers = listOfNotNull(
                            toolServer?.let { command ->
                                McpServer.Stdio(
                                    name = TOOL_SERVER_NAME,
                                    command = command.command,
                                    args = command.args,
                                    env = emptyList(),
                                )
                            },
                        ),
                    ),
                ) { _, _ -> operations }
                info to ioSession
            }
        } catch (e: CancellationException) {
            // The protocol closes its pending requests with a cancellation when the
            // agent's process dies. That is the agent failing to answer — not the
            // caller's cancellation — and the screen needs a failure it can show
            // rather than a cancellation that would leave it loading for ever. The
            // caller's own cancellation is an inactive job, and it is re-raised here.
            logger.d(e) { "The agent stopped before the conversation began." }
            currentCoroutineContext().ensureActive()
            throw AgentUnavailable("The agent stopped before the conversation began.")
        }
        if (handshake == null) {
            // A timeout is a failure the reader can act on, not a cancellation: the
            // screen has to say "the agent did not answer" and offer a way back, and a
            // cancelled connection would leave it loading. `withTimeoutOrNull` says so
            // without catching the cancellation the caller's own scope may be raising.
            throw AgentHandshakeTimeout(
                "The agent did not answer within ${connectTimeout.inWholeSeconds} seconds.",
            )
        }
        session = handshake.second

        return AcpAgentInfo(
            name = handshake.first.implementation?.name.orEmpty(),
            version = handshake.first.implementation?.version.orEmpty(),
        )
    }

    /**
     * Sends a message and reports what the agent does.
     *
     * The flow ends when the turn does. It does not end when the agent stops talking:
     * the completion event is what ends a turn, and its absence is a failure that the
     * caller has to be able to see.
     */
    override fun prompt(message: String): Flow<AcpTurnEvent> {
        val ioSession = session ?: error("The client is not connected")
        return flow {
            emitAll(
                ioSession.prompt(listOf(ContentBlock.Text(message)))
                    .map { event ->
                        when (event) {
                            is Event.SessionUpdateEvent ->
                                AcpEventAdapter.adapt(event.update)?.let { AcpTurnEvent.Activity(it) }

                            is Event.PromptResponseEvent ->
                                AcpTurnEvent.Finished(AcpEventAdapter.outcomeFor(event.response.stopReason))
                        }
                    }
                    .filterNotNull(),
            )
        }
    }

    /**
     * Asks the agent to stop the turn it is running.
     *
     * The agent may finish the turn anyway; the caller decides what that means. A turn
     * that the reader cancelled is [AcpTurnOutcome.Cancelled] whatever the agent says
     * afterwards.
     */
    override suspend fun cancel() {
        runCatchingCancellable { session?.cancel() }
    }

    /**
     * Ends the conversation with the agent.
     *
     * Closing the session is best-effort: the protocol marks it conditional on the
     * agent's capabilities, and an agent that does not support it is not an error. The
     * transport and the process are closed either way — the process tree belongs to the
     * caller, and the caller ends it.
     *
     * ## The goodbye is bounded too
     *
     * The protocol's `session/close` is a request, and a request waits for an answer.
     * The library puts no deadline on it, so an agent that receives the request and
     * never answers it — one that is wedged, one that has already lost the thread —
     * leaves the caller waiting for ever.
     *
     * That is not a corner case to shrug at: this method is what a disconnect calls,
     * and a disconnect that never finishes leaves the screen in the state it was
     * leaving. So the wait has a deadline, and the deadline is short: the answer is a
     * courtesy, not a step the shutdown depends on. The transport and the process are
     * closed either way.
     */
    override suspend fun close() {
        try {
            goodbye()
        } finally {
            session = null
            runCatchingCancellable { transport?.close() }
            transport = null
            runCatchingCancellable { protocol?.close() }
            protocol = null
            client = null
            runCatchingCancellable { tree.close() }
            runCatchingCancellable { process.close() }
        }
    }

    /**
     * Asks the agent to close the session, and gives up when it does not answer.
     *
     * The answer is a courtesy, so every way of not getting one is the same way: the
     * teardown continues. What is not swallowed is a cancellation of the *caller* — that
     * one belongs to the caller and has to survive.
     */
    @Suppress("NoSwallowedCancellation") // the protocol's own cancellation is not the caller's
    private suspend fun goodbye() {
        try {
            val answered = withTimeoutOrNull(closeTimeout) {
                session?.close()
                true
            }
            if (answered == null) {
                logger.d { "The agent did not answer the goodbye; the teardown continues." }
            }
        } catch (e: CancellationException) {
            // The protocol was closed under us: the agent died, or the conversation was
            // already torn down. That cancellation is the protocol's, not the caller's,
            // and the teardown continues. The caller's own cancellation shows up as an
            // inactive job here, and it is re-raised.
            currentCoroutineContext().ensureActive()
            logger.d(e) { "The protocol was closed before the goodbye was answered." }
        }
    }

    /**
     * Ends the agent's process without waiting.
     *
     * The screen is going away and there is no scope left to wait in, so the tree is
     * asked to leave and the pipes are closed. The graceful [close] is the path a
     * disconnect takes.
     */
    override fun terminate() {
        tree.destroyNow()
        runCatching { process.close() }
    }
}

/** The name the agent knows the application's tool server by. */
private const val TOOL_SERVER_NAME = "singularity-todo"

/**
 * How long the agent has to answer the handshake.
 *
 * The screen shows a spinner while it connects and offers no way out of that state, so
 * an agent that never answers would leave the reader looking at a spinner for ever.
 * The bound is generous because the agent may be an `npx` invocation downloading its
 * package on first use.
 */
private val CONNECT_TIMEOUT: Duration = 60.seconds

/**
 * How long the agent has to answer the goodbye.
 *
 * Short, because nothing waits for this answer: the transport and the process are
 * closed whether or not the agent replies, and the reader is waiting for the screen to
 * come back. The protocol library puts no deadline on the request, so this one is
 * ours to set.
 */
private val CLOSE_TIMEOUT: Duration = 5.seconds

/**
 * The agent did not answer the handshake in time.
 *
 * A failure the reader can act on, not a cancellation: the screen says what happened and
 * offers a way back, where a cancelled connection would leave it loading.
 */
class AgentHandshakeTimeout(message: String) : Exception(message)

/**
 * The agent stopped before the conversation began.
 *
 * The protocol reports a dead agent by cancelling its pending requests, which is a
 * cancellation of the protocol's own work and not of the caller's. Translated here, so
 * that the screen has a failure to show instead of a cancellation that would leave it
 * loading for ever.
 */
class AgentUnavailable(message: String) : Exception(message)

/** The version this application reports to the agent. */
private fun appVersionCode(): String =
    runCatching { com.singularity.todo.core.version.appVersion().code.toString() }.getOrDefault("0.0.0")
