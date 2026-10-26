@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.singularity.todo.feature.ai.acp

import co.touchlab.kermit.Logger
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.McpServer
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.PlanEntryStatus
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallStatus as AcpToolCallStatus
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.llm.AgentEvent
import com.singularity.todo.core.llm.AgentTurnOutcome
import com.singularity.todo.core.llm.toolCallSummary
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
import kotlinx.serialization.json.JsonElement
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
    /**
     * How the agent asks the reader for permission.
     *
     * The application's own port, not the protocol's type: the protocol's client
     * operations are built from it at the moment the session is opened, and the
     * protocol never sees the port.
     */
    private val permissions: AcpSessionPermissions,
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
    /**
     * Where the session's lifecycle goes.
     *
     * The client is the only place that knows when the conversation was opened, which
     * session it was, and how many processes the agent left behind — so it is the client
     * that says so.
     */
    private val sessionEvents: AcpLifecycleSink = LoggingAcpLifecycle(),
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
     * Whether the client still holds anything of a connection.
     *
     * Internal, and for the tests only: the fields are private, and a promise that
     * nothing can see is a promise nothing can break. The promise is the one
     * [connect] makes — a connection which did not happen leaves nothing behind —
     * and "nothing" means all four of the fields the connection is made of: the
     * transport, the protocol, the client and the session.
     *
     * A client that held a transport with no session on it would be half a
     * connection, and half a connection is the state [lifecycle] exists to prevent.
     * The session is the only one of the four a caller can see — asking for a turn
     * answers "connected" or "not connected" — so without this the other three
     * could be left behind and every lifecycle test would still be green.
     *
     * It reads the fields without the lock, which is safe for what it is for: a
     * test asks it after the transition it is about has finished, and that
     * transition's writes are already visible to it.
     */
    internal val holdsConnection: Boolean
        get() = transport != null || protocol != null || client != null || session != null

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

            val agentInfo = handshake.first
            // The one comparison the app can make: the agent says which version of the
            // protocol it speaks, and this client speaks one version. An agent that speaks
            // another one may mean something different by the same words, so the connection
            // is refused rather than continued on a guess.
            if (agentInfo.protocolVersion != SUPPORTED_PROTOCOL_VERSION) {
                throw AgentUnavailable(
                    "The agent speaks protocol version ${agentInfo.protocolVersion}; " +
                        "this client speaks version $SUPPORTED_PROTOCOL_VERSION.",
                )
            }

            AcpAgentInfo(
                name = agentInfo.implementation?.name.orEmpty(),
                version = agentInfo.implementation?.version.orEmpty(),
                protocolVersion = agentInfo.protocolVersion,
            ).also { info ->
                sessionEvents.record(
                    AcpSessionEvent.Connected(
                        agentName = info.name,
                        agentVersion = info.version,
                        protocolVersion = info.protocolVersion,
                    ),
                )
                sessionEvents.record(
                    AcpSessionEvent.SessionOpened(sessionId = handshake.second.sessionId.value),
                )
            }
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
                ) { _, _ -> SdkSessionOperations(permissions, sessionEvents) }
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
                                AcpEventAdapter.adapt(event.update.toDomain())?.let { AcpTurnEvent.Activity(it) }

                            is Event.PromptResponseEvent ->
                                AcpTurnEvent.Finished(AcpEventAdapter.outcomeFor(event.response.stopReason.toDomain()))
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
                val terminated: Int = runCatchingCancellable { tree.close() }.getOrDefault(0)
                sessionEvents.record(
                    AcpSessionEvent.ProcessTreeTerminated(processes = terminated),
                )
                runCatchingCancellable { process.close() }
                sessionEvents.record(
                    AcpSessionEvent.Disconnected(reason = "the conversation was closed"),
                )
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
 * The version of the protocol this client speaks.
 *
 * The only version the app can compare: the agent says which version of the protocol it
 * speaks, and a client that speaks another one cannot rely on what the agent means by
 * the same words. The tool server's version is a different matter — the app does not
 * start it, it hands the agent a command — so the app knows which version it offered and
 * cannot see which one ran. See ADR `2026-10-25-the-version-we-can-compare.md`.
 */
private const val SUPPORTED_PROTOCOL_VERSION: Int = 1

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

/**
 * The protocol's client operations, in the application's vocabulary.
 *
 * ## Exactly two methods
 *
 * The protocol's client operations cover four areas — the file system, the terminal,
 * elicitation, and permissions — and this class implements the permissions and nothing
 * else.
 *
 * That is not an omission. The file system, terminal and elicitation methods have
 * defaults in the protocol library, and those defaults **refuse**: an agent that asks
 * to read a file, or to run a command, or to ask the user a question, is told no. The
 * application advertises neither of those capabilities when it initializes, so an
 * agent that honours the protocol never asks. And if one does ask anyway, the refusal
 * is the answer — an implementation that quietly granted access would be a hole in a
 * guarantee the application makes about the user's files.
 *
 * The application's own tool server is how the agent reaches the user's tasks, and it
 * goes through the proposal pipeline, which is where a write becomes a question for the
 * user. An agent with file and terminal access would have a second, quieter way to
 * change the user's data, and it would not ask.
 *
 * ## The permission request
 *
 * A request suspends until the reader answers. The protocol allows exactly one
 * outstanding request per session, so there is no queue to get wrong: the request that
 * arrives is the request on screen.
 */
internal class SdkSessionOperations(
    private val ask: AcpSessionPermissions,
    /**
     * Where the session's lifecycle goes.
     *
     * The permission request is one of the five facts about a session that only the log
     * can answer: the sheet closes when the reader answers, and afterwards the screen
     * shows nothing about the request that was made.
     */
    private val sessionEvents: AcpLifecycleSink,
) : ClientSessionOperations {

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        val request = AcpPermissionRequest(
            toolCallId = toolCall.toolCallId.value,
            title = toolCall.title.orEmpty(),
            options = permissions.map { it.toOption() },
        )
        sessionEvents.record(
            AcpSessionEvent.PermissionRequested(toolCallId = request.toolCallId, title = request.title),
        )
        val decision = ask.requestPermission(request)
        val outcome = when (decision) {
            is PermissionDecision.Selected -> RequestPermissionOutcome.Selected(PermissionOptionId(decision.optionId))
            PermissionDecision.Declined -> refusingOutcome(permissions)
        }
        return RequestPermissionResponse(outcome = outcome)
    }

    /**
     * A notification that is not bound to a prompt.
     *
     * The protocol defines it for agents that have something to say outside a turn.
     * This application has no surface for it, and inventing one would show the reader
     * text nobody asked for — so it is received and dropped.
     */
    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
        // Intentionally empty — see the KDoc above.
    }

    /**
     * How a refusal is expressed on the wire.
     *
     * The protocol has no "rejected" outcome: a refusal is the agent's own rejecting
     * option, chosen on its behalf. When the agent offered no such option, the only
     * honest answer left is that the turn was cancelled before the reader answered.
     */
    private fun refusingOutcome(options: List<PermissionOption>): RequestPermissionOutcome {
        val rejecting = options.firstOrNull { it.kind.isRejection() }
        return rejecting?.let { RequestPermissionOutcome.Selected(PermissionOptionId(it.optionId.value)) }
            ?: RequestPermissionOutcome.Cancelled
    }

    private fun PermissionOption.toOption(): AcpPermissionOption = AcpPermissionOption(
        optionId = optionId.value,
        label = name,
        isRejection = kind.isRejection(),
    )

    private fun PermissionOptionKind.isRejection(): Boolean =
        this == PermissionOptionKind.REJECT_ONCE || this == PermissionOptionKind.REJECT_ALWAYS
}

/**
 * The protocol's session updates, in the application's words.
 *
 * ## Why the translation is here and not in the adapter
 *
 * The adapter's table decides what a reader sees, and it is written in the
 * application's vocabulary so that it can be read and checked without knowing the
 * protocol. This function is the other half: it says what the protocol *did*, and it
 * is the only place that knows what the protocol's variants are.
 *
 * So an upgrade of the library is a diff in this file: a new variant appears here,
 * and the compiler says so, because the `when` below is exhaustive. The reader sees
 * nothing new, because the adapter decides that — and the adapter did not change.
 *
 * The variants that carry nothing for the reader are translated too, into the
 * application's own "nothing" — that is what makes the adapter's ignore list
 * testable without the library.
 */
internal fun SessionUpdate.toDomain(): AcpSessionUpdate = when (this) {
    is SessionUpdate.AgentMessageChunk -> content.textOrNull()
        ?.let { AcpSessionUpdate.TextDelta(it) }
        ?: AcpSessionUpdate.Unrecognised

    is SessionUpdate.ToolCall -> AcpSessionUpdate.ToolCall(
        id = toolCallId.value,
        name = title,
        status = status.toDomain(),
        summary = toolCallSummary(title, rawInput?.toString()),
    )

    is SessionUpdate.ToolCallUpdate -> AcpSessionUpdate.ToolCall(
        id = toolCallId.value,
        name = title.orEmpty(),
        status = status.toDomain(),
        summary = title?.let { toolCallSummary(it, rawInput?.toString()) },
    )

    is SessionUpdate.PlanUpdate -> AcpSessionUpdate.Plan(
        steps = entries.map { entry -> AcpPlanStep(title = entry.content, status = entry.status.toDomain()) },
    )

    // The reader's own words, the agent's private reasoning, the session's settings,
    // and anything this application does not recognise. None of them is activity —
    // the adapter decides that, not this file.
    is SessionUpdate.UserMessageChunk -> AcpSessionUpdate.UserMessage

    is SessionUpdate.AgentThoughtChunk -> AcpSessionUpdate.AgentThought

    is SessionUpdate.PlanUpdateV2,
    is SessionUpdate.PlanRemoved,
    is SessionUpdate.AvailableCommandsUpdate,
    is SessionUpdate.CurrentModeUpdate,
    is SessionUpdate.ConfigOptionUpdate,
    is SessionUpdate.SessionInfoUpdate,
    is SessionUpdate.UsageUpdate,
    -> AcpSessionUpdate.SessionSetting

    is SessionUpdate.UnknownSessionUpdate -> AcpSessionUpdate.Unrecognised
}

/**
 * The agent's four states, one for one.
 *
 * A missing status is a call that has not started: the protocol makes it optional,
 * and the only sensible reading of "no status yet" is "it has not finished".
 */
private fun AcpToolCallStatus?.toDomain(): AcpToolCallState = when (this) {
    AcpToolCallStatus.PENDING -> AcpToolCallState.PENDING
    AcpToolCallStatus.IN_PROGRESS -> AcpToolCallState.IN_PROGRESS
    AcpToolCallStatus.COMPLETED -> AcpToolCallState.COMPLETED
    AcpToolCallStatus.FAILED -> AcpToolCallState.FAILED
    null -> AcpToolCallState.PENDING
}

private fun PlanEntryStatus.toDomain(): AcpPlanStepState = when (this) {
    PlanEntryStatus.PENDING -> AcpPlanStepState.PENDING
    PlanEntryStatus.IN_PROGRESS -> AcpPlanStepState.IN_PROGRESS
    PlanEntryStatus.COMPLETED -> AcpPlanStepState.COMPLETED
}

/** The agent's reasons for stopping, one for one. */
private fun StopReason.toDomain(): AcpStopReason = when (this) {
    StopReason.END_TURN -> AcpStopReason.END_TURN
    StopReason.MAX_TOKENS -> AcpStopReason.MAX_TOKENS
    StopReason.MAX_TURN_REQUESTS -> AcpStopReason.MAX_TURN_REQUESTS
    StopReason.REFUSAL -> AcpStopReason.REFUSAL
    StopReason.CANCELLED -> AcpStopReason.CANCELLED
}

/**
 * The text of a content block, when it is text.
 *
 * An image or a resource in a message is not something to render as a delta of the
 * answer; the reader is here for the words.
 */
private fun ContentBlock.textOrNull(): String? = (this as? ContentBlock.Text)?.text
