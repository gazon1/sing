package com.singularity.todo.mcp

import co.touchlab.kermit.Logger
import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.platformModule
import com.singularity.todo.core.observability.ToolCallContext
import com.singularity.todo.core.version.appVersion
import com.singularity.todo.feature.profile.ProfileBootstrapper
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
import java.io.OutputStreamWriter
import kotlin.time.Clock

private val log = Logger.withTag("Main")

/**
 * MCP server entry point for the Singularity Todo CLI.
 *
 * Usage:
 *   ./gradlew :mcp-server:run --args="--profile=ai-agent"
 *
 * Lifecycle (io.modelcontextprotocol:kotlin-sdk 0.15.0):
 *   - [Server.createSession] is a suspend function that only wires the session and returns;
 *     it does NOT block the JVM.
 *   - The MCP protocol loop runs on three internal coroutines (reader / processor / writer)
 *     rooted in an internally-created SupervisorScope.
 *   - Without an outer `runBlocking { done.join() }` primitive, `main` returns and the JVM
 *     tears down the session coroutines before a single JSON-RPC frame is read.
 *
 *   External Agent ──MCP/stdio──► :mcp-server JVM process
 *                                       │
 *                                       ├─ startKoin { platformModule() + domainModule() }
 *                                       ├─ ToolRegistrar(server, tools).registerAll()
 *                                       ├─ Server.createSession(transport)
 *                                       └─ runBlocking { done.join() } — holds JVM up
 *                                          until the client closes stdin (EOF).
 *
 * All diagnostic logging uses kermit [log]; stdout is reserved for JSON-RPC frames.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val profileId = args.parseProfileArg()

    if (!bootstrapKoin(profileId)) return@runBlocking
    val dependencies = ServerDependencies.fromKoin()
    if (!verifyDatabase(dependencies)) return@runBlocking
    // After Koin is up and DB is reachable, ensure default + agent profiles exist
    // and point DataStore at the agent profile when --profile=ai-agent (or any
    // other agent-like flag) was passed. This makes subsequent tool calls write
    // under the AI Agent's UUID instead of Personal's.
    bootstrapProfiles(dependencies, profileId)

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
        output = System.out.asByteWriteChannel().asSink().buffered(),
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
private fun bootstrapKoin(profileId: String?): Boolean = try {
    startKoin {
        modules(listOf(platformModule(), coreLoggingModule()) + domainModule())
    }
    true
} catch (e: Throwable) {
    log.e(e) { "Koin initialization failed: ${e.message}" }
    writeJsonRpcError(code = -32000, message = "Koin initialization failed: ${e.message}")
    false
}

/**
 * Eagerly opens the Room database and runs a single SELECT to surface schema/migration
 * problems at startup instead of mid-session.
 */
private suspend fun verifyDatabase(dependencies: ServerDependencies): Boolean = try {
    dependencies.database.profileDao().count()
    true
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    log.e(e) { "Database initialization failed: ${e.message}" }
    writeJsonRpcError(code = -32001, message = "Database initialization failed: ${e.message}")
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
                profileId = profileCliArg ?: "ai-agent",
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

/**
 * Move rows whose user_id equals the un-scoped local user into the AI Agent
 * scoped user_id (`"{AI Agent id}/{local user id}"`). Idempotent: no-op if
 * no rows match or if the sqlite3 CLI is unavailable.
 *
 * Done via shell `sqlite3` because this is a one-off bootstrap migration
 * touching DB rows that pre-date the profile model.
 */
private fun retromigrateRowsToAgentScope(profileId: String, localUserId: String, newUserId: String) {
    // Always use the default DB path — Desktop, Android, and MCP all share it now.
    val dbPath = System.getProperty("user.home") + "/.singularity-todo/singularity-todo.db"
    val file = File(dbPath)
    if (!file.exists()) return
    val sql = buildString {
        for (table in listOf("tasks", "notes", "projects", "tags")) {
            append("UPDATE $table SET user_id='").append(newUserId).append("' WHERE user_id='").append(localUserId).append("';")
        }
    }
    try {
        val proc = ProcessBuilder("sqlite3", dbPath).redirectErrorStream(true).start()
        proc.outputStream.use { it.write(sql.toByteArray()) }
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        // sqlite3 prints affected row counts to stdout when group statements
        // use "--changes" (we'll skip that for portability). Better: count
        // before/after by another route, but for the bootstrap we just trust
        // the operation succeeded if exit was 0.
        if (proc.exitValue() == 0) {
            log.i { "Retro-migrated rows to AI Agent scope ($dbPath)" }
        } else {
            log.w { "Retro-migrate failed: $out" }
        }
    } catch (e: Throwable) {
        log.w { "sqlite3 unavailable for retro-migration: ${e.message}" }
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
            "server is exposing and why.",
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
 * Writes what the toolbox looked like at startup, next to the call records.
 *
 * The report needs two things to say "this tool was registered and never called": the
 * records of what happened, and a record of what the toolbox *was*. Without the second,
 * the difference between "never registered" and "never called" is invisible — and the
 * first is not a defect at all.
 *
 * Best-effort: a server that cannot write its registry still serves its tools.
 */

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

private fun installShutdownHook(server: Server) {
    Runtime.getRuntime().addShutdownHook(Thread {
        runBlocking { server.close() }
        stopKoin()
    })
}

// ─── JSON-RPC error response for startup failures ─────────────────────────────

/**
 * Writes a JSON-RPC 2.0 error response to stdout for early startup failures
 * (before the MCP protocol loop begins). This lets the connecting agent
 * receive a structured error instead of silence.
 */
private fun writeJsonRpcError(code: Int, message: String) {
    val error = """{"jsonrpc":"2.0","id":null,"error":{"code":$code,"message":"$message"}}"""
    OutputStreamWriter(System.out, Charsets.UTF_8).use { it.write(error + "\n") }
}

// ─── Profile argument parsing ─────────────────────────────────────────────────

private fun Array<String>.parseProfileArg(): String? = find { it.startsWith("--profile=") }
    ?.substringAfter("=")
    ?.takeIf { it.isNotBlank() }
