# MCP server

> **Reference, not a procedure.** This describes the current state of the MCP server (`:mcp-server`). It goes
> stale whenever the code changes, so a change to it updates this file in the same commit.
> A document that has to be re-read after every refactor is in the wrong place —
> procedures belong in `.agents/skills/`.
>
> Moved out of the skill `singularity-todo-mcp-server` on 2026-10-15; the reason is recorded in
> `docs/decisions/2026-10-15-reference-skills-become-documents.md`.

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

    // Every dependency is read from the container here, once, and passed on as an
    // argument. The server's own code does not know where they came from.
    val dependencies = ServerDependencies.fromKoin()
    if (!verifyDatabase(dependencies)) return@runBlocking

    System.err.println("singularity-todo MCP server started")

    val server = buildServer(dependencies, toolConfiguration, sessionContext)
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

class ToolRegistrar(private val server: Server, private val tools: List<Tool<*, *>>) {

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

## The Agent-Facing Surface

Beyond the domain tools, the server exposes the surface an agent needs to know *what it is
connected to*, *what it may read* and *what it did*.

### `describe_toolbox` — what is exposed, and why

A transport-level tool (not a Koog tool, not in the catalogue, not in Koin): only the
registrar knows both halves — the catalogue says which tools are *declared*, and the
filtering says which are *exposed*.

It reports five separate values, because they answer different questions:

| Value | Source | Question it answers |
|---|---|---|
| `buildVersion` | `core/version/appVersion()` | which release am I? |
| `checkout` | `git rev-parse --short=12 HEAD` | which revision? (`unknown` outside a checkout) |
| `catalogFingerprint` | the catalogue | which *declared* tool definitions? |
| `activeToolsetFingerprint` | the registered tools | which tools are exposed *right now*? |
| `configurationStatus` | the startup snapshot | was the kill switch applied at all? |

The payload also carries `toolset.surfaceContractVersion` — the version of the contract
the surface speaks, owned by the catalogue (`McpToolCatalog.SURFACE_CONTRACT_VERSION`).
The fingerprints say *that* something changed; the version says *whether the shape the
client knows is the shape this server speaks*. A client that reads a renamed field would
otherwise conclude that a tool does not exist, and would have no way to tell.

The version is also in the `initialize` response's `instructions` — so a client knows
which contract it has connected to **before** the first call, rather than after.

The response carries no absolute path, no secret and no remote-config content, and the
disabled-tool list is bounded.

### The kill switch

A tool whose remote-config flag is `false` is **never registered**: it is absent from
`tools/list` and cannot be called. The in-app agent reads the same flags, and both apply
the same rule, `core/ai/filterTools`.

Until 2026-10-20 the switch did nothing anywhere: the tool ID the rule keys on could not
be resolved, so no flag ever matched a tool. The rule was fixed at its owner and the ID
is pinned by a test — see ADR `2026-10-20-the-kill-switch-was-never-applied`. The ID is
now the name the tool is registered under, so the flag key, the catalogue entry and the
name the agent calls are one fact with one owner.

A flag that names **no** tool is reported, not dropped: `describe_toolbox` lists it under
`unmatchedFlags`, and the server logs it at startup. A flag that matched nothing did not
switch anything off, and the operator who set it believes otherwise — so the server says
so rather than staying quiet about it.

**The key space is the tool names.** A flag is keyed by the name the tool is registered
under, so the valid keys are exactly the tool names — and they are declared, not
implied: `config/docs/tool-surface.json` carries them under `flagKeys`, and a test says
the file is current. The gate checks every flag key the *documentation* shows against
that space, so a document that teaches an operator to write a flag matching nothing is
reported rather than believed.

**The payload may only grow.** `describe_toolbox`'s payload and the resource URIs are
contracts with clients that are not upgraded with the server: a new field is fine (a
client ignores what it does not know), a renamed or removed field breaks every client
that reads it. Both are pinned by tests, so a rename fails the build instead of the
clients.

The policy is **fail-open** and says so: when the configuration cannot be read, every tool
is enabled — including one an operator meant to disable. `configurationStatus` reports
`current`, `cached`, `stale` or `unavailable`, so the difference between "no flag" and "no
configuration" is visible. The flags are **global**: the remote-config cache is one row.

### Searching the knowledge

`search_decisions` answers "what did we decide, and is it still open?"; `search_docs`
answers "where is the rule about X?" across the architecture documents, the skills and the
specifications. Both go through the corpus rules in `config/docs/adr-corpus.json` — the
archive is unreachable, the backlog is marked as backlog, and an empty result is
distinguishable from a corpus that was not searched at all.

The answer carries its own version (`SearchEnvelope.schemaVersion`): the envelope is a
contract with an agent, and the corpus policy moved the directories underneath it, so a
client that reads a field it does not know has to be able to tell a newer server from a
misreading.

The *document* corpora have their own policy, `config/docs/document-corpus.json`: the
roots of every scope, how deep the walk goes, and what a document is. It is read by the
search engine (`DocumentCorpusConfig`) and by the Python reader
(`scripts/document_corpus.py`), and `DocumentCorpusReadersAgreeTest` compares the two —
so "which files are documents?" has one answer, not one per reader. The decisions are
not in that file: they are records, with a frontmatter contract and a staleness rule,
and their policy is the ADR one.

`singularity-adr search` is the same search from a terminal, over the same storage.

### Resources

Read-only documents, for the clients that speak them:
`singularity://toolbox`, `singularity://decisions`, `singularity://decision/{slug}`. A slug
is resolved through the corpus rules, never by concatenating it into a path. Client support
for resources varies — the tools are the portable path.

### Tool-call records

Every completed call leaves one versioned, payload-free record: the tool, the outcome, an
error **code**, the duration, the session and the client. No arguments, no result, no
exception text. The measurement happens at the dispatch boundary here, and through a
decorator in the in-app agent; one event schema, two instrumentation points.

The report is local: `python3 scripts/mcp-toolbox-usage.py`.

Both files the report reads declare their version, and the report is strict about the
versions it knows:

- a **call record** carries `schemaVersion`; a record of a version the report does not
  know is counted and named, and is not read as a call;
- the **registry snapshot** carries `schemaVersion` too. Version 1 writes the tool lists
  as objects, the same shape `describe_toolbox` reports; a snapshot written before the
  version existed has no version and its lists are strings — that is version 0. An entry
  that fits neither shape of the version it claims is counted, not guessed at.

The record's *vocabulary* is declared once, in `config/docs/tool-call-records.json`:
the schema version, the outcomes (`OK`, `BUSINESS_ERROR`, `INTERNAL_ERROR`), the
sources and the retention. The Kotlin writer is checked against it by a test, and the
report reads it — so an outcome the report does not know is counted as **unknown** and
named, never as an error. A reader that does not know the vocabulary cannot tell an
error from a word it has not read.

The report says what it could not read: the entries that did not match their version's
shape, the versions it does not know, the outcomes it does not know, and the records
the time window excluded. The window is the **retention** — the writers delete what is
older, so "all the records" is already a bound, and the report names it. It also
reports the error *rate* per tool — a share of the calls, not a second count, because
"3 errors" beside "calls: 3" is a division a reader may not do.

The two sides are compared by a test that runs both: `ToolboxRuntimeContractTest` writes
a snapshot and the records with the production writers and runs the production report
over them.

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
// shared/src/commonMain/kotlin/com/singularity/todo/mcp/ToolAnnotations.kt
package com.singularity.todo.mcp

data class ToolAnnotations(
    val readOnlyHint: Boolean = false,      // get_task, list_tasks, search_tasks
    val destructiveHint: Boolean = false,    // delete_task (a proposal, not a delete)
    val idempotentHint: Boolean = false,    // create_task (same input = same output)
    val openWorldHint: Boolean = false,     // write_adr (creates a file)
)
```

**The annotations live in the catalogue, one entry per tool** — not in a map of their own:

```kotlin
// shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/McpToolCatalog.kt
ToolEntry(
    name = "create_task",
    toolClass = "CreateTaskTool",
    domain = "tasks",
    annotations = ToolAnnotations(idempotentHint = true),
),
```

The registrar looks them up by the tool's **name** — the `NAME` constant, which is also the
name the client calls — so a key that does not match a tool name is an annotation nothing
reads.

This section used to show a `TOOL_ANNOTATIONS` map that no longer existed anywhere, keyed
by names like `tasks.create` that no tool uses, and listing tools the toolbox does not have
(`tasks.complete`, `tasks.restore`, `tags.assign`, `notes.list`). An agent reading it would
have called tools that do not exist — the same defect the catalogue carried, one level down.

The catalogue's `adr` domain is checked against the tool classes by
`scripts/check-skill-drift.py`, so a row that stops being true is caught by the build.

### The surface is also read from the compiler

The gate reads the tool surface out of the Kotlin *sources*, because a gate that runs a
compiler is a gate nobody runs. That has one weakness: a regex that misreads a class does
not fail — it reads less, and the gate reports a surface smaller than the code.

So the same surface is exported from the **compiled** classes:

```bash
./gradlew :shared:exportToolSurface   # writes config/docs/tool-surface.json
```

The export is committed and a test says it is current, and the gate compares what it
parsed with what the compiler saw. The three facts line up: the code declares the
surface, the export is that surface written down, the gate's parser is that surface read
back out of the sources. A parser that misreads a class breaks the last link, and the
gate says so.

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
// In Main.kt shutdown hook — the driver comes from the resolved dependencies,
// not from the container: the server's own code does not reach for the container.
private suspend fun flushWalCheckpoint(driver: SqlDriver) {
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
| `mcp-server/src/main/kotlin/.../mcp/Main.kt` | Entry point, Koin bootstrap, configuration read, server start, shutdown hook |
| `mcp-server/src/main/kotlin/.../mcp/ToolRegistrar.kt` | Koog→MCP adapter, the kill switch, the dispatch measurement |
| `mcp-server/src/main/kotlin/.../mcp/ToolsetSnapshot.kt` | The one snapshot: registered, disabled, identity, configuration status |
| `mcp-server/src/main/kotlin/.../mcp/ToolboxTool.kt` | `describe_toolbox` — what is exposed, and why |
| `mcp-server/src/main/kotlin/.../mcp/ToolboxResources.kt` | the read-only resources: toolbox, decisions, one decision |
| `mcp-server/src/main/kotlin/.../mcp/errors/McpToolError.kt` | Sealed Validation/NotFound/Conflict/Unauthorized/Internal |
| `mcp-server/src/main/kotlin/.../mcp/errors/ErrorMapper.kt` | McpToolError → CallToolResult or throw |
| `mcp-server/src/main/kotlin/.../mcp/ToolAnnotations.kt` | readOnly/destructive/idempotent/openWorld hints |
| `mcp-server/src/main/kotlin/.../mcp/pagination/CursorCodec.kt` | base64(opaque cursor) encode/decode |
| `shared/src/commonMain/kotlin/.../feature/ai/McpToolCatalog.kt` | the declared surface: name, class, domain, annotations |
| `shared/src/commonMain/kotlin/.../core/observability/ToolCallLog.kt` | the tool-call event, its port and the recorder |
| `shared/src/commonMain/kotlin/.../core/ai/RecordingTool.kt` | the in-app decorator that records a call |
| `adr-core/src/commonMain/kotlin/.../tools/AdrSearch.kt` | the search engine, the scopes and the envelope |
| `adr-core/src/commonMain/kotlin/.../tools/DocumentCorpus.kt` | the document corpora policy, read by the search |
| `config/docs/document-corpus.json` | the roots of every document scope, the depth bound, what a document is |
| `config/docs/tool-call-records.json` | the record's version, its vocabulary and its retention |
| `scripts/document_corpus.py` | the Python reader of that policy — compared with the Kotlin one |
| `scripts/mcp-toolbox-usage.py` | the local report over the call records |
| `scripts/changed-modules.py` | which checks a change makes worth running |
| `docs/testing/gate-costs.md` | what the verification loop costs, measured and published |
| `mcp-server/src/test/kotlin/.../mcp/McpServerEndToEndTest.kt` | End-to-end protocol test via the stdio transport |
| `mcp-server/src/test/kotlin/.../mcp/ToolRegistrarTest.kt` | the kill switch, at the place it is applied |
| `mcp-server/src/test/kotlin/.../mcp/ToolboxRuntimeContractTest.kt` | the runtime's writes, read by the production report |

## Related Skills

- `singularity-todo-cli-tool-surface` — write-tool contract: idempotency, authorization, dry-run.
- `singularity-todo-room-multi-instance` — concurrent write safety, `enableMultiInstanceInvalidation`, busy_timeout.
- `singularity-todo-ai-tool` — the underlying `SimpleTool<T>` pattern (every MCP tool wraps one).
- `singularity-todo-multi-profile` — `--profile` argument parsing, profile-aware Koin modules.
- `singularity-todo-llm-usage-tracking` — `UsageRecorder` port injected into tools.
- `singularity-todo-koin-dsl` — `domainModule()` is what the MCP server starts.
- ADR `2026-09-07-dogfooding-mcp-server` — rationale and trade-offs.

<!-- BEGIN GENERATED: adr-tools -->

#### `singularity-adr` — the ADR command line

Run it from the repository root. Every command takes `--json` for
machine-readable output; exit code `2` means the command line was wrong,
`1` means the operation failed.

| Command | What it does |
|---|---|
| `singularity-adr new <slug> <title> [tag ...]` | write a new decision |
| `singularity-adr list` | every decision in the corpus |
| `singularity-adr list-open` | deferred backlog entries still open |
| `singularity-adr read <slug>` | one decision, with its body |
| `singularity-adr search <query>` | find decisions by title, tags or body |
| `singularity-adr update <slug> <status> [by]` | change status, never the body (#509) |
| `singularity-adr stale [--days N] [--status S]` | what the gate will reject next (#521) |
| `singularity-adr prune [--keep slug]` | uncited decisions, never deleted (#525) |
| `singularity-adr validate` | run the repository's ADR status gate |
| `singularity-adr version [--check]` | which build this is, and does it match the checkout |
| `singularity-adr upgrade [--check|--force]` | rebuild this binary when it no longer matches |

Global flags:

- `--json` — machine-readable output on every command (#524)
- `--review-by` — YYYY-MM-DD on update: when this is due to be revisited (#527)

#### MCP tools — `adr` domain

These are the tools an agent calls over the MCP server. The descriptions
below are the same strings the server sends, so they cannot drift.

| Tool | What it does |
|---|---|
| `write_adr` | Create a NEW Architecture Decision Record (ADR) as a Markdown file. Fails if the slug already exists — a decision is never overwritten. To close or revise an existing decision use update_adr, which changes its status and records the supersedes/superseded-by link. To say something new that replaces an old decision, write a new ADR naming the old one in its `supersedes:` frontmatter field, then call update_adr on the old one with status=superseded — do NOT invent a '-v2' slug, it leaves the predecessor unlinked. |
| `list_adrs` | List all Architecture Decision Records sorted by date (newest first). |
| `update_adr` | Change an existing ADR's status frontmatter; the body is never modified. status must be one of: accepted, deferred, superseded, open. status=superseded additionally requires supersededBy, naming an existing slug (a markdown link is accepted). Use this to close a decision once it is settled, and to mark one as replaced after writing its successor — the replacement is a separate write_adr call, not a new '-v2' slug of this one. |
| `read_adr` | Read a specific ADR by its slug (e.g. 2026-09-05-koin-suspend-bridge). |
| `list_open_deferred` | List all deferred backlog entries with OPEN or PARTIAL status. |
| `search_decisions` | Search the decision records by title, tags or body text, newest first. Returns each match with its status, tags, date and a snippet, so an open or superseded decision is visible without reading the file. Use read_adr for the full text of a hit. An empty result means nothing matched; check the 'available' field — false means there was no corpus to search, not that nothing matched. |

#### Before you push

```bash
singularity-adr validate   # what check_adr_status.py will enforce
singularity-adr stale      # decisions the gate will reject next push
```

Both wrap the repository's own Python gates rather than reimplementing them, so a
preview and the gate cannot disagree.

<!-- END GENERATED: adr-tools -->

<!-- BEGIN GENERATED: tool-flags -->

A remote configuration switches a tool off by naming it, so these are the
names it may use. A flag that names nothing matches nothing, and the server
reports it under `unmatchedFlags`.

| Domain | Flag key |
|---|---|
| adr | `list_adrs` |
| adr | `list_open_deferred` |
| adr | `read_adr` |
| adr | `search_decisions` |
| adr | `update_adr` |
| adr | `write_adr` |
| ai | `cluster_notes` |
| ai | `cluster_tasks` |
| ai | `decompose_task` |
| ai | `extract_actions` |
| ai | `generate_checklist` |
| ai | `generate_description` |
| ai | `improve_note` |
| ai | `pick_time` |
| ai | `project_review` |
| ai | `refine_task` |
| ai | `rewrite_note` |
| ai | `smart_rewrite` |
| ai | `suggest_tags` |
| ai | `summarize_note` |
| ai | `weekly_plan` |
| docs | `search_docs` |
| notes | `create_note` |
| notes | `delete_note` |
| notes | `get_note` |
| notes | `update_note` |
| projects | `create_project` |
| projects | `delete_project` |
| projects | `get_project` |
| projects | `list_projects` |
| projects | `update_project` |
| tags | `create_tag` |
| tags | `delete_tag` |
| tasks | `create_task` |
| tasks | `decompose_and_create` |
| tasks | `delete_task` |
| tasks | `get_task` |
| tasks | `list_linked_tasks` |
| tasks | `list_tasks` |
| tasks | `search_tasks` |
| tasks | `update_task` |

<!-- END GENERATED: tool-flags -->
