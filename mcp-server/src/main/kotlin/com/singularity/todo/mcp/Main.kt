package com.singularity.todo.mcp

import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.database.contract.wipeIfNotRoomManaged
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.platformModule
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.ktor.utils.io.asSource
import io.ktor.utils.io.asSink
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.streams.asByteWriteChannel
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
 * Architecture:
 *   External Agent ──MCP/stdio──► :mcp-server JVM process
 *                                       │
 *                                       ├─ startKoin { platformModule() + domainModule() }
 *                                       ├─ Server.createSession(transport) → handles MCP protocol
 *                                       └─ Room KMP driver (same SQLite file as Android/Desktop)
 *
 * Error handling:
 *   Startup errors (Koin, DB) are reported as JSON-RPC error responses to stdout
 *   so the agent knows why the server failed to initialize.
 */
fun main(args: Array<String>): Unit = runBlocking {
    // 1. Parse --profile=NAME argument (profile isolation)
    val profileId = args.parseProfileArg()

    // 2. Bootstrap Koin — same domainModule as Android/Desktop
    try {
        startKoin {
            modules(platformModule(profileId), domainModule())
        }
    } catch (e: Throwable) {
        System.err.println("singularity-todo MCP server: Koin initialization failed: ${e.message}")
        e.printStackTrace(System.err)
        writeJsonRpcError(code = -32000, message = "Koin initialization failed: ${e.message}")
        return@runBlocking
    }

    // 3. Verify database is accessible
    try {
        val db: AppDatabase = GlobalContext.get().get()
        // A simple query to verify the DB opens without corruption
        db.profileDao().count()
    } catch (e: Throwable) {
        System.err.println("singularity-todo MCP server: Database initialization failed: ${e.message}")
        e.printStackTrace(System.err)
        writeJsonRpcError(code = -32001, message = "Database initialization failed: ${e.message}")
        return@runBlocking
    }

    // 4. Log startup info
    System.err.println("singularity-todo MCP server started")

    // 5. Build MCP server
    val server = Server(
        serverInfo = Implementation(
            name = "singularity-todo",
            version = "0.1.0",
            title = null,
            websiteUrl = null,
            icons = emptyList()
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true)
            )
        ),
        instructions = "Singularity Todo MCP server. Use tools to read/write tasks, notes, projects, tags, and ADR entries.",
    ) { /* session initialization callback */ }

    // 6. Register every Koog SimpleTool<T> as an MCP tool
    ToolRegistrar(server).registerAll()

    // 7. Graceful shutdown — close server (WAL checkpoint happens automatically on close)
    Runtime.getRuntime().addShutdownHook(Thread {
        runBlocking {
            server.close()
        }
        stopKoin()
    })

    // 8. Block on stdio transport — NO println, only System.err for logs
    val transport = StdioServerTransport(
        input = System.`in`.toByteReadChannel().asSource().buffered(),
        output = System.out.asByteWriteChannel().asSink().buffered()
    )

    // Create session and block — this starts the MCP protocol loop
    server.createSession(transport)
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

private fun Array<String>.parseProfileArg(): String? {
    return find { it.startsWith("--profile=") }
        ?.substringAfter("=")
        ?.takeIf { it.isNotBlank() }
}

// ─── Profile-aware platformModule ───────────────────────────────────────────

/**
 * Builds a profile-aware [platformModule] that overrides per-profile settings
 * (database path, SecureStorage prefix) when a --profile=CLI argument is passed.
 *
 * When profileId is null, falls back to the default desktop platformModule
 * which uses ~/.singularity-todo/ as the base directory.
 */
private fun platformModule(profileId: String?): org.koin.core.module.Module {
    return if (profileId != null) {
        module {
            includes(platformModule())
            // Override per-profile database path
            single<AppDatabase> {
                val baseDir = File(System.getProperty("user.home"), ".singularity-todo")
                val profileDir = File(baseDir, "profiles/$profileId")
                profileDir.mkdirs()
                val dbPath = File(profileDir, "singularity-todo.db").absolutePath
                wipeIfNotRoomManaged(dbPath)
                AppDatabaseFactory.build(createSqlDriver(), dbPath)
            }
        }
    } else {
        platformModule()
    }
}
