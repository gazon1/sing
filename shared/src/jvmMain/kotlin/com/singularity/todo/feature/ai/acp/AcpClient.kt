@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.singularity.todo.feature.ai.acp

import co.touchlab.kermit.Logger
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
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
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *
 * ## The lifecycle is guarded
 *
 * The connection lives in four fields, and a transition sets them or clears them.
 * Two transitions running at once interleave, and the result is a session bound to
 * a transport that has been closed — a state no reader of the code would expect and
 * no test would have asked for.
 *
 * So the transitions take a lock ([lifecycle]), and the fields that are read outside
 * it are read volatilely. The lock is not decoration: it is what makes "connected"
 * and "not connected" the only two states this client can be in.
 *
 * ## The lock does not make a close slow
 *
 * The lock alone would make a close that arrives during a handshake wait for that
 * handshake — and a handshake may take a minute, because the agent may be an `npx`
 * invocation downloading its package on first use. A reader who pressed Disconnect
 * must not watch the screen for a minute.
 *
 * So a close says so before it waits: it closes the transport, which ends the
 * handshake, and it sets a flag the connect checks as soon as it has built its own
 * transport. Safety and speed are separate questions, and this class answers both.
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

    /**
     * The lock that serialises the two transitions that can wait.
     *
     * The transitions are suspending — the handshake waits for the agent, and the
     * goodbye waits for its answer — so the lock has to be one that can be held
     * across a suspension, and a `Mutex` is that.
     *
     * It is **not** reentrant: a transition that took the lock and then called
     * another transition would wait for a lock it already holds, for ever. That is
     * why no transition calls another one. The teardown they share is
     * [dropConnection], which takes no lock, and it is why [terminate] *tries* the
     * lock instead of taking it.
     */
    private val lifecycle = Mutex()

    /**
     * The transport the conversation runs over.
     *
     * Written under [lifecycle], but read outside it: a close that arrives while the
     * handshake is in flight closes the transport to end that handshake, and it does
     * so before it waits for the lock. The read is a volatile one, so it sees the
     * transport the connect actually built and never a half-written one.
     */
    @Volatile
    private var transport: StdioTransport? = null

    /**
     * Whether the conversation has been asked to end.
     *
     * Set by [close] before it waits for the lock, and read by [connect] once it has
     * built its transport.
     *
     * The two together are what makes a close during a connect **fast** rather than
     * merely safe. The lock alone would make the close wait for the handshake — and
     * the handshake may take a minute, because the agent may be an `npx` invocation
     * downloading its package. A reader who pressed Disconnect must not watch the
     * screen for a minute. So the close says so first, and the connect, which is
     * about to wait for an answer nobody is waiting for any more, notices.
     */
    @Volatile
    private var ending = false

    /**
     * The protocol on top of the transport.
     *
     * Read and written under [lifecycle] only — unlike [transport], which a close
     * that has to end a handshake reads before it waits for the lock.
     */
    private var protocol: Protocol? = null

    /**
     * The client that speaks the protocol.
     *
     * Never read — it is held so that the conversation's objects stay reachable for
     * as long as it lasts, and it is cleared with the rest.
     */
    private var client: Client? = null

    /**
     * The session the agent answers in.
     *
     * This is the one field that is read **outside** the lock: [prompt] is not a
     * suspend function and cannot wait for a lock, and [cancel] has to reach the
     * turn it is stopping while a close is running. So the read is a volatile one:
     * it sees the last write, whole, and never a value a transition is halfway
     * through writing.
     */
    @Volatile
    private var session: ClientSession? = null

    /**
     * Starts the conversation with the agent.
     *
     * The whole body is under [lifecycle]: the fields it sets are the fields
     * [close] clears, and a close that landed between two of the assignments would
     * leave a session bound to a closed transport.
     *
     * @return what the agent says about itself.
     * @throws Exception when the agent cannot be reached, does not speak the protocol,
     *   or answers with a version this client does not speak. The message says which.
     */
    override suspend fun connect(): AcpAgentInfo = lifecycle.withLock {
        val ioTransport = StdioTransport(
            parentScope = scope,
            ioDispatcher = Dispatchers.IO,
            input = process.stdoutLines(),
            output = { line -> process.writeLine(line) },
            name = "acp-stdio",
        )
        transport = ioTransport

        // A connection that did not happen leaves nothing behind: the transport and
        // the protocol this client has just built are closed, and the fields go back
        // to null. Otherwise the client would be left holding half a connection — a
        // transport with no session on it — and only the caller would know to close
        // it. The flag is what says whether the connection happened, because the
        // failure can be a cancellation, and a cancellation is not something to
        // catch here.
        //
        // The block starts as soon as the transport exists, because the transport is
        // the first thing that has to be given back. A close that arrived while this
        // connect was waiting for the lock is noticed here — after the transport is
        // built, so that the teardown has something to close — and a connect that
        // throws for that reason must not leave the transport behind either.
        var connected = false
        try {
            // The close may have arrived while this connect was waiting for the lock, and
            // then the transport it closed was not this one. The request itself is what
            // the connect has to notice, and it notices it here — after the transport is
            // built, so that the teardown below has something to close.
            if (ending) {
                throw AgentUnavailable("The conversation ended before the agent answered.")
            }

            val ioProtocol = Protocol(scope, ioTransport)
            protocol = ioProtocol
            ioProtocol.start()

            val ioClient = Client(ioProtocol)
            client = ioClient

            val handshake = handshakeWith(ioClient)
            session = handshake.second
            connected = true

            AcpAgentInfo(
                name = handshake.first.implementation?.name.orEmpty(),
                version = handshake.first.implementation?.version.orEmpty(),
            )
        } finally {
            if (!connected) dropConnection()
        }
    }

    /**
     * The handshake: the agent says who it is, and a session is opened on it.
     *
     * The handshake is bounded. An agent that never answers — because it is broken,
     * or because it is a bridge that failed to start — must not leave the screen
     * showing a spinner for ever, and the screen offers no way out of that state
     * while it is connecting. The bound is generous because the agent may be an
     * `npx` invocation downloading its package on first use, and a timeout that
     * fired during a download would be a timeout that fired for the wrong reason.
     */
    @Suppress("NoSwallowedCancellation") // the protocol's cancellation is not the caller's
    private suspend fun handshakeWith(ioClient: Client): Pair<AgentInfo, ClientSession> {
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
        return handshake
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
     *
     * ## The whole body is under the lock
     *
     * A close that ran beside a connect could land between two of the assignments
     * the connect makes, and the client would be left holding a session bound to a
     * transport that has been closed. So the fields are cleared under the lock, and
     * the connect and the close are the transitions of one lifecycle, taken one at a
     * time.
     *
     * The close does not *wait* for the connect, though — it ends it. The transport
     * is closed before the lock is taken, which is what makes the handshake fail at
     * once instead of waiting out its deadline. See [ending].
     */
    override suspend fun close() {
        // The request comes first, and it is what makes the close fast. A handshake
        // that is in flight is waiting for an answer that is no longer wanted, and
        // closing the transport is what ends it: the connect fails at once instead of
        // waiting out its deadline, and the lock below is then free.
        //
        // The order of the two matters, and it is the order that makes the whole
        // thing safe rather than merely quick:
        //
        // - the close arrives first: it sets [ending], finds no transport to close,
        //   and the connect sees [ending] as soon as it has built its own;
        // - the connect arrives first: it has built its transport and is waiting for
        //   the agent, and the close's volatile read finds that transport and closes
        //   it, which ends the handshake.
        //
        // Either way the connect ends, and the fields are cleared once, under the
        // lock, by whoever gets there second.
        ending = true

        // The transport goes only when there is nothing to say on it.
        //
        // Without a session there is no conversation to say goodbye to — the
        // handshake never finished — and the transport is the only way to end the
        // wait, so it is closed here, before the lock.
        //
        // With a session the goodbye still has to travel, and the goodbye travels on
        // the transport. Closing it first would turn the close into a failure: the
        // request would go out on a closed channel, and the reader who pressed
        // Disconnect would be met with an exception instead of a closed screen. So
        // the transport stays open until the goodbye has been sent, and the teardown
        // below closes it.
        if (session == null) {
            runCatchingCancellable { transport?.close() }
        }

        lifecycle.withLock {
            try {
                goodbye()
            } finally {
                dropConnection()
                runCatchingCancellable { tree.close() }
                runCatchingCancellable { process.close() }
            }
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
        } catch (e: ClosedSendChannelException) {
            // The transport was closed before the goodbye could be sent. That is the
            // close that ended an in-flight handshake, or a teardown that ran first —
            // either way there is no conversation left to say goodbye to, and the
            // teardown continues. A goodbye that cannot be sent is not a failed
            // close: the close's job is to end the conversation, and it does.
            logger.d(e) { "The transport was closed before the goodbye could be sent." }
        }
    }

    /**
     * Ends the agent's process without waiting.
     *
     * The screen is going away and there is no scope left to wait in, so the tree is
     * asked to leave and the pipes are closed. The graceful [close] is the path a
     * disconnect takes.
     *
     * ## Why the lock is tried and not taken
     *
     * This method cannot suspend: its callers have nowhere to wait — one of them is
     * a ViewModel being cleared. So it cannot wait for the lock either, and it must
     * not take it and hold it: it has no coroutine to release it.
     *
     * So the lock is *tried*. A transition that holds it owns the fields: it is
     * either a close, which clears them itself, or a connect, which fails against
     * the dead process and clears them itself. The fields are cleared here only
     * when no transition is running — which is the case where the client would
     * otherwise be left connected to a process that is gone.
     */
    override fun terminate() {
        ending = true
        tree.destroyNow()
        runCatching { process.close() }
        if (!lifecycle.tryLock()) return
        try {
            dropConnection()
        } finally {
            lifecycle.unlock()
        }
    }

    /**
     * Drops the connection this client holds, and closes what it holds.
     *
     * The four fields go together: [connect] sets them, this clears them, and both
     * happen under [lifecycle]. So the client is connected or it is not, and never
     * in between — which is the whole point of the lock.
     *
     * The process and the tree are not touched here: they belong to the caller, and
     * [close] ends them.
     *
     * It takes no lock, which is what lets the transitions use it: a lock taken
     * twice in the same coroutine is a lock waited for for ever.
     */
    private fun dropConnection() {
        session = null
        runCatchingCancellable { transport?.close() }
        transport = null
        runCatchingCancellable { protocol?.close() }
        protocol = null
        client = null
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
