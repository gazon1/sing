package com.singularity.todo.mcp

import co.touchlab.kermit.Logger
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.platformModule
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.profile.ProfileBootstrapper
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
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
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.File
import java.io.OutputStreamWriter

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
 *                                       ├─ ToolRegistrar(server).registerAll()
 *                                       ├─ Server.createSession(transport)
 *                                       └─ runBlocking { done.join() } — holds JVM up
 *                                          until the client closes stdin (EOF).
 *
 * All diagnostic logging uses kermit [log]; stdout is reserved for JSON-RPC frames.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val profileId = args.parseProfileArg()

    if (!bootstrapKoin(profileId)) return@runBlocking
    if (!verifyDatabase()) return@runBlocking
    // After Koin is up and DB is reachable, ensure default + agent profiles exist
    // and point DataStore at the agent profile when --profile=ai-agent (or any
    // other agent-like flag) was passed. This makes subsequent tool calls write
    // under the AI Agent's UUID instead of Personal's.
    bootstrapProfiles(profileId)

    log.i { "singularity-todo MCP server started" }

    val server = buildServer()
    installShutdownHook(server)

    val transport = StdioServerTransport(
        input = System.`in`.toByteReadChannel().asSource().buffered(),
        output = System.out.asByteWriteChannel().asSink().buffered(),
    )

    val session = server.createSession(transport)
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
private suspend fun verifyDatabase(): Boolean = try {
    GlobalContext.get().get<AppDatabase>().profileDao().count()
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
private suspend fun bootstrapProfiles(profileCliArg: String?) {
    try {
        val bootstrapper: ProfileBootstrapper = GlobalContext.get().get()
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
        val localUserId: String = GlobalContext.get()
            .get<ProfileAwareCurrentUser>()
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

private fun buildServer(): Server {
    val server = Server(
        serverInfo = Implementation(
            name = "singularity-todo",
            version = "0.1.0",
            title = null,
            websiteUrl = null,
            icons = emptyList(),
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
        instructions = "Singularity Todo MCP server. Use tools to read/write tasks, notes, projects, tags, and ADR entries.",
    )
    ToolRegistrar(server).registerAll()
    return server
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
