package com.singularity.todo.mcp

import co.touchlab.kermit.Logger
import com.singularity.todo.core.log.RedactingLogWriter
import com.singularity.todo.core.log.StderrLogWriter
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.ktor.utils.io.asSink
import io.ktor.utils.io.asSource
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.streams.asByteWriteChannel
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import java.io.OutputStreamWriter
import kotlin.system.exitProcess

/**
 * The MCP tool server, as a function rather than an entry point.
 *
 * ## Why the server is a function and not a `main`
 *
 * The server has to run in two places, and they must be the *same* server:
 *
 * - as the standalone `:mcp-server` application, for editors that speak MCP to it;
 * - as the desktop application's own `--mcp-server` mode, which is what an external
 *   agent is handed as its tool access.
 *
 * Two copies of the server would be two toolsets that drift, and the drift would only
 * show up as "the agent cannot see the tool the documentation promises". So the server
 * lives here, in the shared JVM runtime, and the module that ships it standalone is a
 * launcher over this function.
 *
 * ## The lifecycle
 *
 * (io.modelcontextprotocol:kotlin-sdk 0.15.0):
 *   - [Server.createSession] is a suspend function that only wires the session and returns;
 *     it does NOT block the JVM.
 *   - The MCP protocol loop runs on three internal coroutines (reader / processor / writer)
 *     rooted in an internally-created SupervisorScope.
 *   - Without an outer `runBlocking { done.join() }` primitive, the call returns and the JVM
 *     tears down the session coroutines before a single JSON-RPC frame is read.
 *
 *   External Agent ──MCP/stdio──► the tool server's JVM process
 *                                       │
 *                                       ├─ startKoin { platformModule() + domainModule() }
 *                                       ├─ ToolRegistrar(server, tools).registerAll()
 *                                       ├─ Server.createSession(transport)
 *                                       └─ runBlocking { done.join() } — holds JVM up
 *                                          until the client closes stdin (EOF).
 *
 * All diagnostic logging uses kermit and goes to standard error; stdout is reserved
 * for JSON-RPC frames.
 *
 * ## What this function is, and what it is not
 *
 * Five things, and nothing else: it points the log at standard error, it takes the
 * protocol channel, it reads the arguments, it starts the server, and it serves. The
 * start-up itself lives in [startToolServer] and comes back as a [ToolServerStartup] — a
 * server that is ready, or a refusal that carries the code the client is told and the
 * code the process leaves with.
 *
 * The refusals used to be `exitProcess` calls in the middle of the start-up, so that
 * "what does the server do when it cannot start?" could only be answered by reading
 * the whole sequence.
 */
// The process entry point: the JVM has to stay alive until the client closes standard
// input, and a scope that is launched and forgotten would be torn down with the
// `main` frame. `runBlocking` is the one place a blocking bridge belongs.
@Suppress("NoRunBlocking") // the process entry point: the JVM stays up until the client leaves
fun runToolServer(args: Array<String>): Unit = runBlocking {
    // Standard output is the protocol channel and nothing else: from here on, every
    // print in this process — this application's log, a library's start-up banner —
    // goes to standard error. See [ProtocolStdout] for why a client that parses
    // standard output strictly would otherwise lose the tool access entirely.
    // The logger is pointed at standard error before anything can be logged, so the
    // server's own log lines never depend on the redirect that follows. See
    // [StderrLogWriter] for why the two are kept apart.
    installStderrLogging()
    val protocolOut = ProtocolStdout.reserve()

    val selection = parseProfileSelection(args)

    when (val startup = startToolServer(selection)) {
        is ToolServerStartup.Refused -> {
            // The refusal is a value, not an exit buried in the middle of a sequence:
            // the client is told why on the protocol channel, and the process leaves
            // with the code the refusal names.
            writeJsonRpcError(protocolOut, startup.code, startup.message)
            exitProcess(startup.exitCode)
        }

        is ToolServerStartup.Ready -> serve(startup.server, startup.sessionContext, protocolOut)
    }
}

/**
 * Serves the client until it closes standard input.
 *
 * ## Why the process stays up
 *
 * The session is created and the call returns; the protocol loop runs on the SDK's own
 * coroutines. Without something that waits for the session to close, the JVM would
 * tear those coroutines down before a single JSON-RPC frame is read. So the wait
 * belongs here, and only here.
 */
private suspend fun serve(server: Server, sessionContext: SessionContextHolder, protocolOut: ProtocolStdout) {
    val transport = StdioServerTransport(
        input = System.`in`.toByteReadChannel().asSource().buffered(),
        output = protocolOut.asByteWriteChannel().asSink().buffered(),
    )

    val session = server.createSession(transport)
    // The session is known immediately; the client's name is not — it arrives with the
    // `initialize` request, which the client sends after this point. The holder reads it
    // when a call is made, which is the only moment it is worth having.
    sessionContext.session = session
    val done = Job()
    session.onClose { done.complete() }
    done.join()
}

// ─── JSON-RPC error response for startup failures ─────────────────────────────

/**
 * Points the logger at standard error.
 *
 * The protocol owns standard output, so the log cannot go there. Saying so here —
 * rather than letting a global redirect carry the log there as a side effect — is
 * what makes "the protocol owns standard output" a contract the code keeps rather
 * than an arrangement it happens to have.
 */
private fun installStderrLogging() {
    Logger.setLogWriters(RedactingLogWriter(StderrLogWriter()))
}

/**
 * Writes a JSON-RPC 2.0 error response to stdout for early startup failures
 * (before the MCP protocol loop begins). This lets the connecting agent
 * receive a structured error instead of silence.
 *
 * The body is built by the serialiser, so a message containing a quote or a newline is
 * still a valid protocol message. The message itself is **neutral**: the internal
 * detail of the failure goes to the log, because the caller is not the party that can
 * act on it and a file path is not a thing an agent needs to be told.
 */
private fun writeJsonRpcError(protocolOut: ProtocolStdout, code: Int, message: String) {
    val stream = protocolOut.asPrintStream()
    OutputStreamWriter(stream, Charsets.UTF_8).use { it.write(jsonRpcError(code, message) + "\n") }
}
