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
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

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

    // session.onClose + Job.join() is what holds the JVM up.
    // SDK 0.15.0 — Server.createSession only wires the session and returns;
    // without an external blocking primitive, main() exits and the
    // internal reader/processor/writer coroutines die with it.
    val session = server.createSession(transport)
    val done = Job()
    session.onClose { done.complete() }
    done.join()
}
```

**Critical details:**
- **`session.onClose + Job.join()` is mandatory.** The SDK does not block on its own — this is the canonical pattern from the upstream `samples/weather-stdio-server/.../McpWeatherServer.kt:55-62`. See ADR `2026-09-07-mcp-stdio-blocking-lifecycle` for the full rationale and regression test.
- `installShutdownHook(server)` — backstop for SIGTERM; normal EOF exits flow through `session.onClose → done.complete() → done.join() returns → JVM exits cleanly`.
- `runBlocking` at top level is **acceptable** here — this is a CLI main, not a hot path. Same reasoning as `koinBridge` (see ADR `2026-09-05-koin-suspend-bridge`).
- `System.in` / `System.out` — stdio transport. **Never** `println` for logs (corrupts the JSON-RPC stream). Use `System.err` or kermit Logger.

## ToolRegistrar — Koog → MCP Adapter

Each Koog `Tool<*, *>` bean (resolved from Koin's `aiToolsCoreModule()`) becomes one MCP tool. The adapter is split across two files:

- `ToolRegistrar.kt` — registration loop and per-call handler.
- `schema/KoogJsonSchemaBuilder.kt` — `ToolDescriptor` → MCP `ToolSchema` (JSON Schema 2020-12).
- `schema/KoogJsonElementAdapter.kt` — bridges kotlinx-serialization `JsonElement` ↔ Koog `JSONElement` via `JsonElement.toKoog()`.

```kotlin
// mcp-server/src/main/kotlin/com/singularity/todo/mcp/ToolRegistrar.kt
package com.singularity.todo.mcp

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.Tool
import ai.koog.serialization.kotlinx.KotlinxSerializer
import com.singularity.todo.mcp.schema.KoogJsonSchemaBuilder
import com.singularity.todo.mcp.schema.toKoog
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TaskSupport
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolExecution
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.koin.core.context.GlobalContext

class ToolRegistrar(private val server: Server) {

    private val koin = GlobalContext.get()
    @Suppress("UNCHECKED_CAST")
    private val tools: List<Tool<*, *>> = koin.get()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = KotlinxSerializer(json)

    fun registerAll() {
        tools.forEach { registerOne(it) }
    }

    private fun registerOne(tool: Tool<*, *>) {
        val descriptor = tool.descriptor
        server.addTool(
            name = descriptor.name,
            description = descriptor.description,
            inputSchema = KoogJsonSchemaBuilder.build(descriptor),
            outputSchema = "",
            toolSchema = KoogJsonSchemaBuilder.build(descriptor), // output schema, kept identical
            annotations = TOOL_ANNOTATIONS[descriptor.name]?.toMcpAnnotations(),
            toolExecution = ToolExecution(TaskSupport.Optional),
            output = JsonObject(emptyMap()),
        ) { request: CallToolRequest -> handleToolCall(tool, request) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleToolCall(tool: Tool<*, *>, request: CallToolRequest): CallToolResult {
        val koogArgs = ai.koog.serialization.JSONObject(
            request.arguments?.mapValues { (_, v) -> v.toKoog() } ?: emptyMap()
        )
        return try {
            val koogTool = tool as SimpleTool<Any>
            val args: Any = koogTool.decodeArgs(koogArgs, serializer)
            val resultString: String = runBlocking { koogTool.execute(args) }
            CallToolResult(
                content = listOf(TextContent(text = resultString)),
                structuredContent = runCatching {
                    json.parseToJsonElement(resultString).let { it as? kotlinx.serialization.json.JsonObject }
                }.getOrNull(),
                isError = false,
            )
        } catch (e: Exception) {
            CallToolResult(
                content = listOf(TextContent(text = "[InternalError] ${e.message ?: "unknown"}")),
                isError = true,
            )
        }
    }
}
```

**Critical:**
- The 9-argument `Server.addTool` overload is positional; missing the `outputSchema` string parameter is a common typo.
- The result `String` is already JSON-encoded by the existing Koog tool (see `singularity-todo-ai-tool`). Forward it as-is inside `TextContent`.
- `structuredContent` — attempt to parse the result; fall back to `null` if the tool did not return valid JSON.

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
    "write_adr" to ToolAnnotations(openWorldHint = true),
    "list_adrs" to ToolAnnotations(readOnlyHint = true),
    "read_adr" to ToolAnnotations(readOnlyHint = true),
    "list_open_deferred" to ToolAnnotations(readOnlyHint = true),
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

Add to `~/.zcode/mcp/servers.toml` (check `zcode-guide:zcode-configuration-guide` for exact
location). Replace `/absolute/path/to/your/clone` below with the absolute path of **your**
clone — it has to be absolute because the editor launches the server from its own working
directory:

```toml
[[servers]]
name = "singularity-todo-agent"
command = ["./gradlew", ":mcp-server:run", "--quiet", "--args=--profile=ai-agent"]
cwd = "/absolute/path/to/your/clone"
description = "MCP server for AI-agent dogfooding"

[[servers]]
name = "singularity-todo-personal"
command = ["./gradlew", ":mcp-server:run", "--quiet", "--args=--profile=personal"]
cwd = "/absolute/path/to/your/clone"
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
- `singularity-todo-koin-dsl` — `domainModule()` is what the MCP server starts.
- ADR `2026-09-07-dogfooding-mcp-server` — rationale and trade-offs.
