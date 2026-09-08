package com.singularity.todo.mcp

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.database.contract.wipeIfNotRoomManaged
import com.singularity.todo.core.di.domainModule
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.ktor.utils.io.asSink
import io.ktor.utils.io.asSource
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.streams.asByteWriteChannel
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.io.OutputStreamWriter

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
 * Diagnostic logging goes to stderr; stdout is reserved for JSON-RPC frames.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val profileId = args.parseProfileArg()

    if (!bootstrapKoin(profileId)) return@runBlocking
    if (!verifyDatabase()) return@runBlocking

    System.err.println("singularity-todo MCP server started")

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
 * Starts Koin with the platform (CLI-only) module and the shared [domainModule].
 * On failure, writes a JSON-RPC error to stdout and returns false.
 */
private fun bootstrapKoin(profileId: String?): Boolean = try {
    startKoin {
        modules(platformModule(profileId), *domainModule().toTypedArray())
    }
    true
} catch (e: Throwable) {
    System.err.println("singularity-todo MCP server: Koin initialization failed: ${e.message}")
    e.printStackTrace(System.err)
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
} catch (e: Throwable) {
    System.err.println("singularity-todo MCP server: Database initialization failed: ${e.message}")
    e.printStackTrace(System.err)
    writeJsonRpcError(code = -32001, message = "Database initialization failed: ${e.message}")
    false
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

// ─── Profile-aware platformModule ─────────────────────────────────────────────

/**
 * Builds a profile-aware [org.koin.core.module.Module] that overrides per-profile
 * settings (database path) when a --profile=NAME argument is passed.
 *
 * IMPORTANT: We inline all platform bindings here rather than using `includes()`
 * because `includes()` inside a `module {}` block creates a child scope in Koin 4,
 * making those bindings invisible to sibling modules at the root scope.
 *
 * When profileId is null, falls back to the default desktop platformModule
 * which uses ~/.singularity-todo/ as the base directory.
 */
private fun platformModule(profileId: String?): org.koin.core.module.Module = module {
    val dbPath = if (profileId != null) {
        val baseDir = File(System.getProperty("user.home"), ".singularity-todo")
        val profileDir = File(baseDir, "profiles/$profileId")
        profileDir.mkdirs()
        File(profileDir, "singularity-todo.db").absolutePath
    } else {
        System.getProperty("user.home") + "/.singularity-todo/singularity-todo.db"
    }
    File(dbPath).parentFile?.mkdirs()
    wipeIfNotRoomManaged(dbPath)
    single<AppDatabase> { AppDatabaseFactory.build(createSqlDriver(), dbPath) }

    single { get<AppDatabase>().taskDao() }
    single { get<AppDatabase>().noteDao() }
    single { get<AppDatabase>().projectDao() }
    single { get<AppDatabase>().tagDao() }
    single { get<AppDatabase>().syncOutboxDao() }
    single { get<AppDatabase>().attachmentDao() }
    single { get<AppDatabase>().reminderDao() }
    single { get<AppDatabase>().checklistDao() }
    single { get<AppDatabase>().llmUsageDao() }
    single { get<AppDatabase>().profileDao() }

    single<androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>> {
        PreferenceDataStoreFactory.create {
            File(System.getProperty("user.home") + "/.singularity-todo/settings.preferences_pb").also {
                it.parentFile?.mkdirs()
            }
        }
    }

    single<com.singularity.todo.core.security.SecureStoragePort> {
        com.singularity.todo.core.security.JvmSecureStorage()
    }
    single<com.singularity.todo.core.notifications.NotificationPort> {
        com.singularity.todo.core.notifications.JvmNotificationPort()
    }
    single<com.singularity.todo.core.files.FileSystem> {
        com.singularity.todo.core.files.JvmFileSystem()
    }
    single<com.singularity.todo.core.files.FileRevealer> {
        com.singularity.todo.core.files.JvmFileRevealer()
    }
    single<com.singularity.todo.core.backup.BackupCodec> {
        com.singularity.todo.core.backup.JvmBackupCodec()
    }
    single<String> { System.getProperty("user.home") + "/.singularity-todo/backups" }
}
