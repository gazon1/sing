package com.singularity.todo.mcp

import co.touchlab.kermit.Logger
import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.log.RedactingLogWriter
import com.singularity.todo.core.log.StderrLogWriter
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.platformModule
import com.singularity.todo.core.observability.ToolCallContext
import com.singularity.todo.core.process.Subprocess
import com.singularity.todo.core.version.appVersion
import com.singularity.todo.feature.ai.McpToolCatalog
import com.singularity.todo.feature.profile.ProfileBootstrapper
import com.singularity.todo.feature.profile.ProfileId
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.ktor.utils.io.asSink
import io.ktor.utils.io.asSource
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.streams.asByteWriteChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.File
import kotlin.system.exitProcess
import java.io.OutputStreamWriter
import kotlin.time.Clock

private val log = Logger.withTag("Main")

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
 * All diagnostic logging uses kermit [log]; stdout is reserved for JSON-RPC frames.
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
    //
    // The logger is pointed at standard error before anything can be logged, so the
    // server's own log lines never depend on the redirect that follows. See
    // [StderrLogWriter] for why the two are kept apart.
    installStderrLogging()
    val protocolOut = ProtocolStdout.reserve()
    val selection = parseProfileSelection(args)

    // Both arguments at once is a request that says two different things, and neither of
    // them can be obeyed. Failing here is the honest answer; picking one would silently
    // serve a profile the caller did not ask for.
    if (selection is ProfileSelection.Ambiguous) {
        log.e {
            "Both --profile and --profile-id were given; refusing to guess which profile to use."
        }
        writeJsonRpcError(
            protocolOut,
            code = -32002,
            message = "Conflicting profile arguments: pass either --profile or --profile-id, not both.",
        )
        exitProcess(EXIT_USAGE)
    }

    val fixedProfileId: String? = (selection as? ProfileSelection.FixedId)?.id
    val legacyLabel: String? = (selection as? ProfileSelection.LegacyLabel)?.name

    if (legacyLabel != null) {
        log.w {
            "--profile is deprecated and will be removed; use --profile-id with a profile id. " +
                "The label still works, and still means what it always meant."
        }
    }

    if (!bootstrapKoin(fixedProfileId, protocolOut)) exitProcess(EXIT_STARTUP)
    val dependencies = resolveDependencies(protocolOut) ?: exitProcess(EXIT_STARTUP)
    if (!verifyDatabase(dependencies, protocolOut)) exitProcess(EXIT_STARTUP)
    // A profile that does not exist is not a profile the server may serve: the tools
    // would write under an id nobody owns, and the user would find their tasks
    // missing rather than told. Never a fallback.
    if (fixedProfileId != null && !profileExists(dependencies, fixedProfileId)) {
        log.e { "No profile with id $fixedProfileId" }
        writeJsonRpcError(protocolOut, code = -32002, message = "No profile with that id exists.")
        exitProcess(EXIT_USAGE)
    }
    // After Koin is up and DB is reachable, ensure default + agent profiles exist
    // and point DataStore at the agent profile when --profile=ai-agent (or any
    // other agent-like flag) was passed. This makes subsequent tool calls write
    // under the AI Agent's UUID instead of Personal's.
    bootstrapProfiles(dependencies, legacyLabel)

    // The remote configuration is read once, here, and the answer is handed to the
    // registrar. Reading it per tool call would let the exposed toolbox change under a
    // running client; reading it once means the toolbox a client sees is the toolbox the
    // server announced.
    val toolConfiguration = readToolConfiguration(dependencies)
    log.i {
        "Tool configuration: ${toolConfiguration.status}, " +
            "${toolConfiguration.flags.count { it.value == false }} tool(s) disabled"
    }

    log.i { "singularity-todo MCP server started" }

    val sessionContext = SessionContextHolder()
    val server = buildServer(dependencies, toolConfiguration, sessionContext)
    installShutdownHook(server)

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

// ─── Bootstrap helpers ────────────────────────────────────────────────────────

/**
 * Starts Koin with the shared JVM platform [platformModule] and the shared [domainModule].
 *
 * The binding set is deliberately the SAME one the desktop app uses — a hand-rolled
 * subset here drifted and left `SyncWorkScheduler`/proposal DAOs unbound, which killed
 * `ToolRegistrar` at startup. Profile data isolation is handled post-Koin by
 * [ProfileBootstrapper.run] via the `user_id` scope on each DAO query; the
 * `--profile=NAME` argument is a label, not a directory suffix.
 *
 * On failure, writes a JSON-RPC error to stdout and returns false.
 */
// The server's start-up has to fail in one place, whatever the failure was: a missing
// dependency, a locked store, a broken native library. The caller gets a JSON-RPC error
// and a non-zero exit code, not a stack trace.
@Suppress("TooGenericExceptionCaught") // start-up has to fail in one place, whatever the failure was
private fun bootstrapKoin(fixedProfileId: String?, protocolOut: ProtocolStdout): Boolean = try {
    startKoin {
        // A process that was told which profile to serve never opens the store that holds
        // the active profile — the store takes a file lock, and the application is holding
        // it. That is decided by the module list, not by anything the server does later:
        // the profile repository is built without a store, and the tools read their one
        // setting through the narrow port, which answers with the default rather than
        // pulling the application's settings store in with it.
        //
        // The active profile has a file of its own — `profile_state.preferences_pb` — so
        // the settings file this server does open, for the AI configuration, does not
        // say anything about which profile the user is working in.
        modules(
            listOf(platformModule(), coreLoggingModule()) + domainModule(fixedProfileId?.let(ProfileId::fromString)),
        )
    }
    true
} catch (e: Throwable) {
    log.e(e) { "Koin initialization failed: ${e.message}" }
    writeJsonRpcError(
        protocolOut,
        code = -32000,
        message = "The server could not start: its dependencies are unavailable.",
    )
    false
}

/**
 * Resolves the server's dependencies, or says on the protocol channel why it could not.
 *
 * The same one-place failure as the rest of the start-up. Resolving the tool list
 * builds every collaborator the server has, and any of them can fail to build — a
 * missing binding, a host that cannot be read, a database that cannot be opened.
 * Left unguarded, such a failure escaped `main`: a stack trace on standard error and
 * an exit, with **nothing on the protocol channel at all**. A client waiting for an
 * answer saw the connection close and no reason, which is the one answer a client
 * cannot act on.
 */
// The same one-place failure as the rest of the start-up: a dependency that cannot be
// built is a start-up failure, and the caller gets a JSON-RPC error and a non-zero exit
// code, not a stack trace.
@Suppress("TooGenericExceptionCaught") // start-up has to fail in one place, whatever the failure was
private fun resolveDependencies(protocolOut: ProtocolStdout): ServerDependencies? = try {
    ServerDependencies.fromKoin()
} catch (e: Throwable) {
    log.e(e) { "The server's dependencies could not be resolved: ${e.message}" }
    writeJsonRpcError(
        protocolOut,
        code = -32000,
        message = "The server could not start: its dependencies are unavailable.",
    )
    null
}

/** Whether a profile with this id exists. */
private suspend fun profileExists(dependencies: ServerDependencies, profileId: String): Boolean =
    dependencies.database.profileDao().getById(profileId) != null

/**
 * Eagerly opens the Room database and runs a single SELECT to surface schema/migration
 * problems at startup instead of mid-session.
 */
// The same one-place failure as the start-up: the database either opens or it does not.
@Suppress("TooGenericExceptionCaught") // the database either opens or it does not
private suspend fun verifyDatabase(dependencies: ServerDependencies, protocolOut: ProtocolStdout): Boolean = try {
    dependencies.database.profileDao().count()
    true
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    log.e(e) { "Database initialization failed: ${e.message}" }
    writeJsonRpcError(protocolOut, code = -32001, message = "The server could not open its database.")
    false
}

/**
 * Seeds default profiles (Personal + AI Agent) and switches the active profile
 * to one matching the host's `--profile=NAME` argument, when reasonable.
 *
 * Mapping rule:
 *  - null / "default" / "personal" → leave whatever DataStore had (usually Personal)
 *  - "ai-agent" / "agent" → seed & activate "AI Agent"
 *  - any other name → don't activate (the user opted into a custom profile that
 *    the bootstrapper is unaware of)
 *
 * If activating AI Agent and the DB already has rows owned by the un-scoped
 * local userId (from earlier dogfooding runs before profiles existed), retro-migrate
 * them under the AI Agent's scoped userId so they become visible after switching
 * in the UI. Safe to run repeatedly: only touches rows owned by `user_id = LOCAL_UID`.
 */
// The profile bootstrap is best-effort: a failure here leaves the server on the
// personal/default profile, which is a working server.
@Suppress("TooGenericExceptionCaught") // best-effort: the server works on the default profile
private suspend fun bootstrapProfiles(dependencies: ServerDependencies, profileCliArg: String?) {
    try {
        val bootstrapper: ProfileBootstrapper = dependencies.profileBootstrapper
        val activateName = when (profileCliArg?.lowercase()) {
            "ai-agent", "agent" -> "AI Agent"
            "default", "personal", null -> null
            else -> null
        }

        // The un-scoped local userId, read from the session BEFORE the switch.
        //
        // It must not come from `ProfileAwareCurrentUser.current`: that is a cached
        // StateFlow that a background collector rewrites, and `bootstrapper.run` is
        // suspend, so it gives that collector a chance to run. Once it has, `current`
        // is already the agent-scoped id, and the migration below would build
        // newUserId = "{agent}/{agent}/{user}" — an id no profile matches, leaving
        // every migrated row unreachable. Whether that happened depended on dispatcher
        // timing. The session is not touched by the switch, so this read is exact.
        val localUserId: String = dependencies.currentUser
            .liveLocalUserId
            .first()
            .value

        val result = bootstrapper.run(
            seedExtras = listOf(ProfileBootstrapper.SeedProfile.AI_AGENT),
            activateName = activateName,
        )
        result.activated?.let { activated ->
            // Retro-migrate rows from the unscoped local user id.
            val agentId = activated.value
            retromigrateRowsToAgentScope(
                localUserId = localUserId,
                newUserId = "$agentId/$localUserId",
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        log.e(e) { "Profile bootstrap failed: ${e.message}" }
        // Non-fatal: the rest of the server can still operate against the
        // personal/default profile.
    }
}

/** The server could not start: its dependencies or its database are unavailable. */
private const val EXIT_STARTUP = 1

/** The server was asked for something it cannot do: conflicting or unknown arguments. */
private const val EXIT_USAGE = 2

/**
 * Move rows whose user_id equals the un-scoped local user into the AI Agent
 * scoped user_id (`"{AI Agent id}/{local user id}"`). Idempotent: no-op if
 * no rows match or if the sqlite3 CLI is unavailable.
 *
 * Done via shell `sqlite3` because this is a one-off bootstrap migration
 * touching DB rows that pre-date the profile model.
 *
 * The shell-out goes through [Subprocess] rather than a `ProcessBuilder` of its own:
 * spawning a process is confined to that one file so that every subprocess in the
 * repository is drained and closed the way that does not deliver SIGPIPE to the child.
 */
private fun retromigrateRowsToAgentScope(localUserId: String, newUserId: String) {
    // Always use the default DB path — Desktop, Android, and MCP all share it now.
    val dbPath = System.getProperty("user.home") + "/.singularity-todo/singularity-todo.db"
    val file = File(dbPath)
    if (!file.exists()) return
    val sql = buildString {
        for (table in listOf("tasks", "notes", "projects", "tags")) {
            append(
                "UPDATE $table SET user_id='",
            ).append(newUserId).append("' WHERE user_id='").append(localUserId).append("';")
        }
    }
    // sqlite3 prints affected row counts to stdout when group statements use
    // "--changes" (we'll skip that for portability). Better: count before/after by
    // another route, but for the bootstrap we just trust the operation succeeded if
    // the exit was 0 — which is exactly what a non-null result means here.
    val result = Subprocess.runCapturing(listOf("sqlite3", dbPath), stdin = sql)
    if (result != null) {
        log.i { "Retro-migrated rows to AI Agent scope ($dbPath)" }
    } else {
        log.w { "Retro-migration did not run: sqlite3 is unavailable or the update failed" }
    }
}

/**
 * The remote-config flags that decide which tools are exposed, and how they were read.
 *
 * Read through the existing port, which is bounded and absorbs its own failures: a
 * timeout yields the last known good snapshot, and with no cached snapshot at all the
 * defaults are returned — which enable every tool.
 *
 * The policy is **fail-open**: a tool that an operator switched off is exposed again
 * when the configuration cannot be read. That is a deliberate choice — the flags are an
 * operational control for a broken tool, not a safety boundary — and it is why the
 * status travels with the flags all the way to `describe_toolbox`, so an operator can
 * see that the switch was *not* applied rather than assume it was.
 */
// The configuration is fail-open: an unreadable configuration exposes every tool, which
// is a deliberate choice and not an accident.
@Suppress("TooGenericExceptionCaught") // fail-open by design: the flags are an operational control
private suspend fun readToolConfiguration(dependencies: ServerDependencies): ToolConfiguration = try {
    val snapshot = dependencies.remoteConfig.snapshot()
    ToolConfiguration(
        flags = snapshot.mcpToolFlags,
        status = ToolsetSnapshot.statusOf(snapshot, Clock.System.now().toEpochMilliseconds()),
        fetchedAtEpochMillis = snapshot.fetchedAtEpochMillis.takeIf { it > 0L },
    )
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    log.w { "Remote configuration unavailable, every tool is enabled: ${e.message}" }
    ToolConfiguration(
        flags = emptyMap(),
        status = ConfigurationStatus.ERROR,
        fetchedAtEpochMillis = null,
    )
}

/** The flags that decide the exposed toolbox, and where they came from. */
data class ToolConfiguration(
    val flags: Map<String, Boolean>,
    val status: ConfigurationStatus,
    val fetchedAtEpochMillis: Long?,
)

private fun buildServer(
    dependencies: ServerDependencies,
    configuration: ToolConfiguration,
    sessionContext: SessionContextHolder,
): Server {
    val server = Server(
        serverInfo = Implementation(
            name = "singularity-todo",
            // The real application version, from the canonical source the desktop app
            // and the Android build both use. A literal here would tell a client it had
            // connected to a version nobody could check against the checkout.
            version = appVersion().name,
            title = null,
            websiteUrl = null,
            icons = emptyList(),
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
                resources = ServerCapabilities.Resources(listChanged = true),
            ),
        ),
        instructions = "Singularity Todo MCP server. Use tools to read/write tasks, notes, " +
            "projects, tags, and ADR entries. Call describe_toolbox to see which tools this " +
            "server is exposing and why. Surface contract version: " +
            "${McpToolCatalog.SURFACE_CONTRACT_VERSION}.",
    )
    val registrar = ToolRegistrar(
        server = server,
        tools = dependencies.tools,
        toolFlags = configuration.flags,
        configurationStatus = configuration.status,
        configurationFetchedAtEpochMillis = configuration.fetchedAtEpochMillis,
        recorder = dependencies.recorder,
        sessionContext = { sessionContext.current() },
    )
    registrar.registerAll()
    ToolboxTool(registrar.snapshot).register(server)
    ToolboxResources(
        snapshot = registrar.snapshot,
        storage = dependencies.adrStorage,
    ).register(server)
    RegistrySnapshot.write(registrar.snapshot, dependencies.dataDirectory)
    log.i {
        "Toolbox: ${registrar.snapshot.registered.size} tool(s) registered, " +
            "${registrar.snapshot.disabled.size} disabled, " +
            "fingerprint ${registrar.snapshot.identity.activeToolsetFingerprint}"
    }
    if (registrar.snapshot.unmatchedFlags.isNotEmpty()) {
        // A flag that names no tool switched nothing off. The operator who set it
        // believes it did, so the server says so rather than staying quiet.
        log.w {
            "Remote configuration names ${registrar.snapshot.unmatchedFlags.size} tool(s) " +
                "this server does not expose: ${registrar.snapshot.unmatchedFlags.joinToString()}"
        }
    }
    return server
}

/**
 * Who is connected, and in which session.
 *
 * Late-bound, because the session is created *after* the tools are registered, and the
 * tool calls it describes happen after that. A holder rather than a constructor
 * argument, for the same reason the registrar takes a supplier: the value is not known
 * when the thing that needs it is built.
 */
private class SessionContextHolder {
    @Volatile
    var session: ServerSession? = null

    fun current(): ToolCallContext {
        val session = this.session
        return ToolCallContext(
            sessionId = session?.sessionId,
            client = session?.clientVersion?.let { "${it.name}@${it.version}" },
        )
    }
}

// A shutdown hook has no coroutine context of its own, and the JVM is on its way out:
// the hook has to block until the session is closed, because after it returns the JVM
// tears the process down whatever the session was doing.
@Suppress("NoRunBlocking") // a shutdown hook has to block: the JVM is leaving
private fun installShutdownHook(server: Server) {
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runBlocking { server.close() }
            stopKoin()
        },
    )
}

// ─── JSON-RPC error response for startup failures ─────────────────────────────

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

private fun writeJsonRpcError(protocolOut: ProtocolStdout, code: Int, message: String) {
    OutputStreamWriter(protocolOut.asPrintStream(), Charsets.UTF_8).use { it.write(jsonRpcError(code, message) + "\n") }
}
