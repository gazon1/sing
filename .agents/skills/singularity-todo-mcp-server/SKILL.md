---
name: singularity-todo-mcp-server
description: MCP server pattern for Singularity Todo KMP. Use when adding a new :mcp-server Gradle module that exposes existing Koog AI tools as MCP tools to external agents (Claude Code, ZCode CLI, Cursor). Covers io.modelcontextprotocol:kotlin-sdk:0.15.0 Server + StdioTransport, addTool registration, JSON-Schema adapter from Koog SimpleTool<T>, graceful shutdown with PRAGMA wal_checkpoint(PASSIVE), and ZCode MCP config wiring. Read this BEFORE adding write-tools or running the MCP server.
---

# MCP Server — Exposing AI Tools to External Agents

## Overview

The AI agent lives inside the KMP app via `KoogAgentService`. To dogfood from an external terminal agent (ZCode, Claude Code, Cursor), expose the same tools through **Model Context Protocol** over stdio. The `:mcp-server` JVM module is **not** a parallel implementation — it's a thin transport that delegates to the same `domainModule()` Koin graph the Android/Desktop apps use.

```
External Agent ──MCP/stdio──► :mcp-server JVM process
                                  │
                                  ├─ startKoin { platformModule() + domainModule() }
                                  ├─ Server.addTool(name, schema) { input → koogTool.execute(decoded) }
                                  └─ Room KMP driver (same file as Android/Desktop)
```

Single source of truth: existing repositories in `shared/src/commonMain/.../feature/`. No new domain logic in `:mcp-server`.

## When to Use This Skill

- Adding a new `:mcp-server` Gradle module that hosts an MCP server.
- Adding a new AI tool that should be **exposed to external agents** (not just internal Koog agent loop).
- Debugging stdio transport issues, JSON-Schema mismatch, or Koin DI failures inside the CLI.
- Wiring ZCode / Claude Code to consume the MCP server.
- Understanding the full request lifecycle: CLI arg parsing → Koin bootstrap → Server start → tool registration → graceful shutdown.

Skip for: in-app Koog agent loop (`ChatViewModel`), read-only tools that the LLM does not need externally, or one-off bash scripts.

## Gradle Module Setup

```kotlin
// mcp-server/build.gradle.kts
plugins {
    alias(libs.plugins.kotlinJvm)              // JVM-only, NOT multiplatform
    alias(libs.plugins.kotlinSerialization)
    application                                 // provides JavaExec + mainClass
}

application {
    mainClass.set("com.singularity.todo.mcp.MainKt")
}

kotlin {
    jvmToolchain(17)                           // MCP SDK requires JVM 17+
}

dependencies {
    implementation(project(":shared"))           // repos + DI graph
    implementation("io.modelcontextprotocol:kotlin-sdk:0.15.0")
    implementation(libs.kermit)                // logging parity
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly(libs.kotlinx.cli)             // for --profile arg parsing
}
```

**Why `kotlinJvm`, not `kotlinMultiplatform`:** the CLI is JVM-only. Reuse the multiplatform `:shared` library as a dependency — it provides all repositories and DI.

**Why `application` plugin:** gives `gradlew :mcp-server:run` and a runnable distribution. Required for ZCode MCP-config to invoke the server.

## Main.kt Skeleton

```kotlin
// mcp-server/src/main/kotlin/com/singularity/todo/mcp/Main.kt
package com.singularity.todo.mcp

import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.platformModule
import io.modelcontextprotocol.kotlin.sdk.Server
import io.modelcontextprotocol.kotlin.sdk.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.stdio.StdioServerTransport
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

fun main(args: Array<String>): Unit = runBlocking {
    // 1. Parse --profile=NAME argument (see singularity-todo-multi-profile)
    val profileId = args.parseProfileArg()

    // 2. Bootstrap Koin — same domainModule as Android/Desktop
    startKoin {
        modules(platformModule(profileId), domainModule(profileId))
    }

    // 3. Build MCP server
    val server = Server(
        serverInfo = ServerInfo(name = "singularity-todo", version = "0.1.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(
                    listChanged = true   // allow dynamic tool list updates
                )
            )
        )
    )

    // 4. Register every Koog SimpleTool<T> as an MCP tool
    ToolRegistrar(server).registerAll()

    // 5. Graceful shutdown — flush WAL, close server
    Runtime.getRuntime().addShutdownHook(Thread {
        runBlocking {
            flushWalCheckpoint()
            server.close()
        }
        stopKoin()
    })

    // 6. Block on stdio transport — NO println, only System.err for logs
    server.connect(StdioServerTransport(System.`in`, System.out))
}
```

**Critical details:**
- `addShutdownHook` — without it, SIGINT leaves SQLite WAL uncheckpointed and the next Android open sees stale data.
- `runBlocking` at top level is **acceptable** here — this is a CLI main, not a hot path. Same reasoning as `koinBridge` (see ADR `2026-09-05-koin-suspend-bridge`).
- `System.in` / `System.out` — stdio transport. **Never** `println` for logs (corrupts the JSON-RPC stream). Use `System.err` or kermit Logger.

## ToolRegistrar — Koog → MCP Adapter

Each Koog `SimpleTool<T>` becomes one MCP tool. The adapter handles JSON decode/encode so the LLM sees a clean JSON-Schema input.

```kotlin
// mcp-server/src/main/kotlin/com/singularity/todo/mcp/ToolRegistrar.kt
package com.singularity.todo.mcp

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.Tool
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.schema.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.koin.core.context.GlobalContext
import kotlin.reflect.typeOf

class ToolRegistrar(private val server: Server) {

    private val koin = GlobalContext.get()
    // All Tool<*, *> beans from aiToolsCoreModule()
    private val tools: List<Tool<*, *>> = koin.get()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun registerAll() {
        tools.forEach { tool -> registerOne(tool) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun registerOne(tool: Tool<*, *>) {
        val name = tool.name
        val description = tool.description
        // Koog TypeToken → JSON Schema 2020-12
        val inputSchema = tool.inputSchema.toJsonSchema()

        server.addTool(
            name = name,
            description = description,
            inputSchema = inputSchema,
            annotations = tool.annotations  // readOnlyHint, destructiveHint, idempotentHint
        ) { request: CallToolRequest ->
            val input = json.decodeFromJsonElement(tool.inputSerializer, request.arguments)
            val koogTool = tool as SimpleTool<Any>
            val result = runBlocking { koogTool.execute(input) }
            CallToolResult(
                content = listOf(TextContent(text = result)),
                structuredContent = json.decodeFromString<JsonElement>(result),
                isError = false,
            )
        }
    }
}
```

**Critical:**
- `tool.inputSerializer` — Koog exposes `TypeToken` which has `.javaType`. Use `json.serializersModule.serializer(tool.inputSerializer.javaType)`.
- The result `String` is already JSON-encoded by the existing Koog tool (see `singularity-todo-ai-tool`). Forward it as-is inside `TextContent`.
- `structuredContent` — pass the decoded JSON element so clients can access typed fields.

## Two-Tier Error Model

From MCP spec 2025-06-18: **protocol errors** → JSON-RPC `error` object (`-32602` invalid params, `-32603` internal). **Business errors** → `isError: true` in a successful result with actionable text.

```kotlin
// mcp-server/src/main/kotlin/com/singularity/todo/mcp/errors/McpToolError.kt
package com.singularity.todo.mcp.errors

sealed interface McpToolError {
    data class Validation(val field: String, val message: String) : McpToolError
    data class NotFound(val resource: String, val id: String) : McpToolError
    data class Conflict(val reason: String) : McpToolError
    data class Unauthorized(val resource: String) : McpToolError
    data class Internal(val message: String, val cause: Throwable? = null) : McpToolError
}

fun McpToolError.format(): String = when (this) {
    is Validation -> "[Validation] $field: $message"
    is NotFound -> "[NotFound] $resource '$id' not found"
    is Conflict -> "[Conflict] $reason"
    is Unauthorized -> "[Unauthorized] Cannot modify $resource — belongs to another user"
    is Internal -> "[InternalError] $message${cause?.let { " (${it.message})" } ?: ""}"
}
```

```kotlin
// mcp-server/src/main/kotlin/com/singularity/todo/mcp/errors/ErrorMapper.kt
package com.singularity.todo.mcp.errors

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent

fun ErrorMapper.toResult(error: McpToolError): CallToolResult = when (error) {
    is Internal -> throw McpInternalException(error.format(), error.cause)
    else -> CallToolResult(
        content = listOf(TextContent(text = error.format())),
        isError = true,
    )
}
```

Rule: **never** expose stack traces or internal paths in `Validation/NotFound/Conflict/Unauthorized`. The LLM reads the text and self-corrects.

## Tool Annotations

From MCP spec 2025-06-18, tool annotations are **hints** (untrusted by spec) but clients like Claude Code show warnings:

```kotlin
// mcp-server/src/main/kotlin/com/singularity/todo/mcp/ToolAnnotations.kt
package com.singularity.todo.mcp

data class ToolAnnotations(
    val readOnlyHint: Boolean = false,      // ✅ get_task, list_tasks, search_tasks
    val destructiveHint: Boolean = false,    // ✅ delete_task, restore_task (trash)
    val idempotentHint: Boolean = false,    // ✅ create_task (same input = same output)
    val openWorldHint: Boolean = false,     // ✅ write_adr (creates external file)
)

val TOOL_ANNOTATIONS = mapOf(
    "tasks.create" to ToolAnnotations(idempotentHint = true),
    "tasks.update" to ToolAnnotations(idempotentHint = true),
    "tasks.complete" to ToolAnnotations(idempotentHint = true),
    "tasks.delete" to ToolAnnotations(destructiveHint = true),
    "tasks.restore" to ToolAnnotations(),
    "tasks.list" to ToolAnnotations(readOnlyHint = true),
    "tasks.get" to ToolAnnotations(readOnlyHint = true),
    "tasks.search" to ToolAnnotations(readOnlyHint = true),
    "projects.list" to ToolAnnotations(readOnlyHint = true),
    "projects.get" to ToolAnnotations(readOnlyHint = true),
    "notes.create" to ToolAnnotations(idempotentHint = true),
    "notes.list" to ToolAnnotations(readOnlyHint = true),
    "notes.get" to ToolAnnotations(readOnlyHint = true),
    "tags.create" to ToolAnnotations(idempotentHint = true),
    "tags.assign" to ToolAnnotations(),
    "adr.write" to ToolAnnotations(openWorldHint = true),
    "adr.list" to ToolAnnotations(readOnlyHint = true),
    "adr.read" to ToolAnnotations(readOnlyHint = true),
    "decompose_and_create" to ToolAnnotations(),
)
```

## Pagination

All `list` tools use opaque cursor pagination. Page size: 50.

```kotlin
// mcp-server/src/main/kotlin/com/singularity/todo/mcp/pagination/CursorCodec.kt
package com.singularity.todo.mcp.pagination

import kotlinx.serialization.Serializable
import java.util.Base64

@Serializable
data class Cursor(val offset: Int, val sortKey: String)

fun Cursor.encode(): String = Base64.getUrlEncoder()
    .encodeToString(Cursor.serializer().stringToByte())
    .trimEnd('=')

fun String.decodeCursor(): Cursor? = runCatching {
    val padded = this + "=".repeat((4 - length % 4) % 4)
    val bytes = Base64.getUrlDecoder().decode(padded)
    Cursor.serializer().fromBytes(bytes)
}.getOrNull()

// Usage in list tool:
val nextCursor = if (items.size == PAGE_SIZE) {
    Cursor(offset = input.cursor?.let { it.decodeCursor()?.offset ?: 0 } + PAGE_SIZE, sortKey = sortKey).encode()
} else null
```

## ZCode MCP Config

Add to `~/.zcode/mcp/servers.toml` (check `zcode-guide:zcode-configuration-guide` for exact location):

```toml
[[servers]]
name = "singularity-todo-agent"
command = ["./gradlew", ":mcp-server:run", "--quiet", "--args=--profile=ai-agent"]
cwd = "/home/max/AndroidStudioProjects/singularity_cllone_kmp"
description = "MCP server for AI-agent dogfooding"

[[servers]]
name = "singularity-todo-personal"
command = ["./gradlew", ":mcp-server:run", "--quiet", "--args=--profile=personal"]
cwd = "/home/max/AndroidStudioProjects/singularity_cllone_kmp"
description = "MCP server for personal tasks"
```

**Tip:** use `installDist` to avoid Gradle startup overhead:
```bash
./gradlew :mcp-server:installDist
# Then in servers.toml:
command = ["./mcp-server/build/install/mcp-server/bin/mcp-server", "--profile=ai-agent"]
```

## Graceful Shutdown — WAL Flush

```kotlin
// In Main.kt shutdown hook:
private suspend fun flushWalCheckpoint() {
    val driver = GlobalContext.get().get<SqlDriver>()
    // PASSIVE — never TRUNCATE (blocks writers, can deadlock Android)
    driver.execute(null, "PRAGMA wal_checkpoint(PASSIVE)", 0)
}
```

**Rule:** `PRAGMA wal_checkpoint(TRUNCATE)` is forbidden. Use `PASSIVE` only.

See `singularity-todo-room-multi-instance` for the full cross-process concurrency pattern.

## Schema Version Guard

On startup, verify the Room schema version matches the binary's expectation:

```kotlin
// In Main.kt, before starting server:
private fun checkSchemaVersion(driver: SqlDriver) {
    val userVersion = driver.query("PRAGMA user_version", emptyList()) {
        it.getLong(0)
    }.single()
    val expected = AppDatabase.Config.schemaVersion  // e.g., 8
    if (userVersion < expected) {
        System.err.println("""
            |WARNING: Database schema is older than this binary.
            |  Binary expects: $expected
            |  Database is at: $userVersion
            |  Run migrations or set SINGULARITY_IGNORE_SCHEMA_SKEW=1 to ignore.
        """.trimMargin())
    }
}
```

Beads uses `BD_IGNORE_SCHEMA_SKEW=1` as the escape hatch. Follow the same naming pattern.

## Common Mistakes

```kotlin
// ❌ WRONG — println corrupts the JSON-RPC stream
println("Server started")

// ✅ CORRECT — stderr only
System.err.println("Server started")

// ❌ WRONG — StdioServerTransport with no args
val transport = StdioServerTransport()

// ✅ CORRECT
val transport = StdioServerTransport(System.`in`, System.out)

// ❌ WRONG — TRUNCATE blocks writers
driver.execute("PRAGMA wal_checkpoint(TRUNCATE)")

// ✅ CORRECT — PASSIVE is non-blocking
driver.execute("PRAGMA wal_checkpoint(PASSIVE)")

// ❌ WRONG — throw in tool handler for business error
throw NotFoundException("task not found")

// ✅ CORRECT — return isError=true
CallToolResult(content = listOf(TextContent("[NotFound] task 'xyz' not found")), isError = true)

// ❌ WRONG — 500-char tool description
val description = "This tool creates a task. It takes a title and optional description..." // too long

// ✅ CORRECT — 50-150 chars
const val DESCRIPTION = "Create a new task with title, optional description, priority, and project."
```

## Files Reference

| File | Purpose |
|---|---|
| `mcp-server/build.gradle.kts` | Gradle module, `application` plugin, `kotlin-sdk:0.15.0` dependency |
| `mcp-server/src/main/kotlin/.../mcp/Main.kt` | Entry point, Koin bootstrap, server start, shutdown hook |
| `mcp-server/src/main/kotlin/.../mcp/ToolRegistrar.kt` | Koog→MCP adapter, registers all `Tool<*, *>` beans |
| `mcp-server/src/main/kotlin/.../mcp/errors/McpToolError.kt` | Sealed Validation/NotFound/Conflict/Unauthorized/Internal |
| `mcp-server/src/main/kotlin/.../mcp/errors/ErrorMapper.kt` | McpToolError → CallToolResult or throw |
| `mcp-server/src/main/kotlin/.../mcp/ToolAnnotations.kt` | readOnly/destructive/idempotent/openWorld hints map |
| `mcp-server/src/main/kotlin/.../mcp/pagination/CursorCodec.kt` | base64(opaque cursor) encode/decode |
| `mcp-server/src/test/kotlin/.../mcp/ChannelTransportTest.kt` | End-to-end protocol test via ChannelTransport |

## Related Skills

- `singularity-todo-cli-tool-surface` — write-tool contract: idempotency, authorization, dry-run.
- `singularity-todo-room-multi-instance` — concurrent write safety, `enableMultiInstanceInvalidation`, busy_timeout.
- `singularity-todo-ai-tool` — the underlying `SimpleTool<T>` pattern (every MCP tool wraps one).
- `singularity-todo-multi-profile` — `--profile` argument parsing, profile-aware Koin modules.
- `singularity-todo-llm-usage-tracking` — `UsageRecorder` port injected into tools.
- `singularity-todo-koin-di` — `domainModule()` is what the MCP server starts.
- ADR `2026-09-07-dogfooding-mcp-server` — rationale and trade-offs.
