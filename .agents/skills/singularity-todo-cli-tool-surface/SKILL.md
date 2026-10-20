---
name: singularity-todo-cli-tool-surface
description: Write-tool surface contract for Singularity Todo KMP MCP server. Use when adding a new write SimpleTool<T> that modifies state (create/update/delete). Covers idempotencyKey, authorization (CurrentUser), dryRun, two-tier error mapping, pagination for list tools, and schema versioning. All 17+ write tools follow this contract.
---

# Write-Tool Surface Contract

## Overview

Every **write** tool in the AI agent exposes a `SimpleTool<T>` that modifies state. Unlike read tools, write tools must handle:

1. **Idempotency** — LLM may retry the same call
2. **Authorization** — user can only modify their own resources
3. **Dry-run** — destructive operations should support preview mode
4. **Error mapping** — business errors → `isError: true`, internal errors → JSON-RPC `-32603`
5. **Token usage recording** — every write tool should record usage via `UsageRecorder`

Read tools (`get_task`, `list_tasks`, `search_tasks`, etc.) follow the simpler pattern from `singularity-todo-ai-tool`.

## Standard Input DTO Pattern

```kotlin
@Serializable
data class <Tool>Input(
    // ── Authorization ──────────────────────────────────────────
    /** Always required. CurrentUser.userId is injected by the tool. */
    // ── Idempotency ────────────────────────────────────────────
    /** Optional. Skip if already processed (cache key = toolName + idempotencyKey). */
    val idempotencyKey: String? = null,
    // ── Dry-run ────────────────────────────────────────────────
    /** If true, validate but do not persist. Default: false (real write). */
    val dryRun: Boolean = false,
    // ── Pagination (list tools only) ───────────────────────────
    val cursor: String? = null,
    val limit: Int = 50,
    // ── Tool-specific fields ───────────────────────────────────
    val title: String,
    val description: String? = null,
    // ...
)

@Serializable
data class <Tool>Output(
    val id: String,
    val status: String,
    val dryRunSkipped: Boolean = false,  // only set when dryRun=true
    val nextCursor: String? = null,        // only for list tools
    val cached: Boolean = false,            // true when idempotencyKey matched
    val tokensUsed: Int? = null,           // filled by UsageRecorder
)
```

## Idempotency

LLM may call a tool multiple times (retry, parallel execution). Use `idempotencyKey` to return the same result without re-executing.

```kotlin
class <Tool>Tool(
    private val repo: <Repo>,
    private val usageRecorder: UsageRecorder,
    private val idempotencyCache: IdempotencyCache,
) : SimpleTool<<Tool>Input>(...) {

    override suspend fun execute(args: <Tool>Input): String {
        // 1. Check idempotency cache
        args.idempotencyKey?.let { key ->
            idempotencyCache.get("$NAME:$key")?.let { cached ->
                return cached.copy(cached = true).toJson()
            }
        }

        // 2. Dry-run: validate without persisting
        if (args.dryRun) {
            validateInput(args)
            return <Tool>Output(
                id = "(dry-run)",
                status = "validated",
                dryRunSkipped = true
            ).toJson()
        }

        // 3. Real execution
        val result = repo.create(args.toDomain()).getOrThrow()

        // 4. Record usage
        usageRecorder.record(ToolUsageEvent(
            toolName = NAME,
            modelId = null,           // set by MCP server layer
            inputTokens = 0,
            outputTokens = 0,
            durationMs = /* measure */,
            profileId = currentUser.profileId,
        ))

        // 5. Cache result
        args.idempotencyKey?.let { key ->
            idempotencyCache.put("$NAME:$key", output)
        }

        return output.toJson()
    }
}
```

**IdempotencyCache implementation:**
```kotlin
// shared/src/commonMain/.../core/observability/IdempotencyCache.kt
interface IdempotencyCache {
    suspend fun get(key: String): <Tool>Output?
    suspend fun put(key: String, value: <Tool>Output, ttlSeconds: Long = 3600)
}

class RoomIdempotencyCache(private val dao: IdempotencyDao) : IdempotencyCache {
    override suspend fun get(key: String) = dao.find(key)?.let { json ->
        Json.decodeFromString<<Tool>Output>(json)
    }
    override suspend fun put(key: String, value: <Tool>Output, ttlSeconds: Long) {
        dao.upsert(IdempotencyRecord(key, Json.encodeToString(value), System.currentTimeMillis() + ttlSeconds * 1000))
    }
}
```

Cache TTL: 1 hour. Sufficient for LLM retry windows.

## Authorization

Every write tool must verify the resource belongs to the current user before mutating:

```kotlin
override suspend fun execute(args: <Tool>Input): String {
    // Load the resource to check ownership
    val existing = repo.watchById(args.id).first()

    // Authorization check — an actionable failure goes in the OUTPUT, not in an
    // exception: `McpToolError` lives in `:mcp-server` and is not on this classpath.
    if (existing == null || existing.userId != currentUser.userId) {
        return Json.encodeToString(
            <Tool>Output.serializer(),
            <Tool>Output(error = "no task '${args.id}' for this user"),
        )
    }

    // Proceed with mutation...
}

private val currentUser: CurrentUser  // injected
```

**Rule:** an authorization failure is reported with actionable text — **never** an
HTTP-style code, and never an exception from a tool in `:shared`. The LLM reads the
message and self-corrects. See [Two-Tier Error Mapping](#two-tier-error-mapping) for
where the two tiers actually live.

## Dry-Run for Destructive Operations

For tools that **delete**, **archive**, or **permanently remove**:

```kotlin
@Serializable
data class DeleteTaskInput(
    val taskId: String,
    /** If true, simulate deletion and return affected records without persisting. */
    val dryRun: Boolean = true,   // ← default TRUE for destructive
    val idempotencyKey: String? = null,
)

data class DeleteTaskOutput(
    val wouldDelete: List<String>,   // task IDs that would be deleted
    val dryRunSkipped: Boolean,
    val cached: Boolean = false,
)
```

**Why `dryRun = true` by default for destructive?** Safety. The LLM must explicitly pass `dryRun = false` to perform the actual deletion. This mirrors the Beads pattern: "NEVER delete without explicit confirmation."

**Exception:** `complete_task`, `pin_task`, `set_priority` — not destructive, `dryRun = false` by default.

## Two-Tier Error Mapping

**Where the two tiers live matters, and this used to be wrong here.**

`McpToolError` is declared in **`:mcp-server`**, not in `:shared`. A tool in
`:shared` cannot throw it — the class is not on its classpath — so a tool that tries
raises `unresolved reference` and does not compile. The two tiers are therefore split
across the boundary:

- **In the tool (`:shared`)** — a failure the caller can act on is reported in the
  *output*, as an `error` field. The tool returns a JSON string; it does not throw a
  transport-level error.
- **At the boundary (`:mcp-server`)** — the registrar catches what the tool threw and
  maps it: a business failure to `isError = true` with actionable text, anything else to
  JSON-RPC `-32603`.

```kotlin
// In :shared — the tool reports the failure the caller can act on.
@Serializable
data class DeleteTaskOutput(
    val wouldDelete: List<String>,
    val dryRunSkipped: Boolean = false,
    val cached: Boolean = false,
    val error: String? = null,       // the actionable failure, in the output
)

class DeleteTaskTool(private val repo: TaskRepository) :
    SimpleTool<DeleteTaskInput>(TypeToken.of(DeleteTaskInput::class.java), NAME, DESCRIPTION) {
    override suspend fun execute(args: DeleteTaskInput): String {
        val result = runCatchingCancellable { repo.delete(args.id) }
        return Json.encodeToString(
            DeleteTaskOutput.serializer(),
            DeleteTaskOutput(
                wouldDelete = listOf(args.id),
                dryRunSkipped = args.dryRun,
                error = result.exceptionOrNull()?.message,
            ),
        )
    }
}
```

```kotlin
// In :mcp-server — the boundary decides what a failure means.
try {
    val result = tool.execute(args)
    CallToolResult(content = listOf(TextContent(result)), isError = false)
} catch (e: McpToolError) {
    ErrorMapper.toResult(e)          // business → isError = true
} catch (e: Exception) {
    ErrorMapper.toResult(McpToolError.Internal(e.message ?: "unknown", e))  // → -32603
}
```

**Rule:** a tool in `:shared` never imports `McpToolError`. It reports an actionable
failure in its output; the boundary turns that into `isError = true`. The LLM reads the
message and self-corrects; internals are logged server-side.

## Tool Annotation Convention

Every write tool must have the correct annotation in `ToolAnnotations.kt`:

The annotation is looked up by the tool's **name** — the `NAME` constant, which is also
the name the client calls. A key that does not match a tool name is an annotation nothing
reads, so the keys below are the tool names.

| Tool | Annotation |
|---|---|
| `create_task` | `idempotentHint = true` |
| `update_task` | `idempotentHint = true` |
| `delete_task` | `destructiveHint = true` |
| `list_tasks` | `readOnlyHint = true` |
| `get_task` | `readOnlyHint = true` |
| `search_tasks` | `readOnlyHint = true` |
| `list_linked_tasks` | `readOnlyHint = true` |
| `create_project` | `idempotentHint = true` |
| `update_project` | `idempotentHint = true` |
| `delete_project` | `destructiveHint = true` |
| `list_projects` | `readOnlyHint = true` |
| `get_project` | `readOnlyHint = true` |
| `create_note` | `idempotentHint = true` |
| `update_note` | `idempotentHint = true` |
| `delete_note` | `destructiveHint = true` |
| `get_note` | `readOnlyHint = true` |
| `create_tag` | `idempotentHint = true` |
| `delete_tag` | `destructiveHint = true` |
| `write_adr` | `openWorldHint = true` (creates a file outside the database) |
| `update_adr` | `openWorldHint = true` (rewrites a file) |
| `list_adrs` | `readOnlyHint = true` |
| `read_adr` | `readOnlyHint = true` |
| `list_open_deferred` | `readOnlyHint = true` |
| `search_decisions` | `readOnlyHint = true` |
| `search_docs` | `readOnlyHint = true` |
| `decompose_and_create` | none |

The table is generated from `McpToolCatalog.kt`, and the drift gate checks the `adr`
domain of it against the tool classes — so a row that stops being true is caught.

## Pagination for List Tools

```kotlin
@Serializable
data class ListTasksInput(
    val status: String? = "active",
    val projectId: String? = null,
    val cursor: String? = null,
    val limit: Int = 50,   // max 100
)

@Serializable
data class ListTasksOutput(
    val tasks: List<TaskSummary>,
    val nextCursor: String?,
    val totalCount: Int?,   // only when cursor=null
) {
    companion object {
        const val PAGE_SIZE = 50
    }
}
```

- Cursor is **opaque** — encode `{offset, sortKey}` as base64. Never expose internal DB offsets.
- `limit` capped at 100 to prevent abuse.
- `totalCount` only returned on first page (cursor=null) to allow LLM to estimate pages.

## Schema Versioning

If a tool's input schema changes (new field, renamed field, changed type):

1. **Bump `schemaVersion`** in the tool annotation:
   ```kotlin
   companion object {
       const val NAME = "tasks.create"
       const val SCHEMA_VERSION = 2  // incremented on breaking change
   }
   ```
2. **Maintain backwards compatibility** for at least 1 major version — old clients with old schemas should get a `Validation` error with a helpful message, not crash.
3. Document the migration in the tool's `description`.

## Complete Tool List

The **names** below are the names the client calls — the `NAME` constant of each tool
class, and the key the annotations are looked up by. They are owned by
`McpToolCatalog.kt`, and the drift gate checks the catalogue against the tool classes.

A list of tools that do not exist is worse than no list: an agent reads it and calls
`tasks.pin`, and the call fails. This table was one, and it is corrected here — the
tools `tasks.pin`, `tasks.restore`, `tasks.complete`, `tasks.set_priority`,
`tasks.set_due_date`, `tasks.move_to_project`, `notes.archive` and `notes.pin` were
listed and **do not exist**.

| Tool | Input | Authorization | Destructive |
|---|---|---|---|
| `create_task` | title, description?, priority?, kind?, projectId?, parentTaskId?, tagIds? | N/A (new) | no |
| `update_task` | taskId, title?, description?, priority?, kind?, projectId?, tagIds?, dueDate?, dueTime?, someday? | `task.userId == currentUser` | no |
| `delete_task` | taskId, reason? | `task.userId == currentUser` | **yes** — a proposal that needs confirmation |
| `create_project` | name, color? | N/A (new) | no |
| `update_project` | projectId, name?, color?, icon?, description? | `project.userId == currentUser` | no |
| `delete_project` | projectId, reason? | `project.userId == currentUser` | **yes** — a proposal that needs confirmation |
| `create_note` | title, bodyMarkdown, isFolder?, parentNoteId? | N/A (new) | no |
| `update_note` | noteId, title?, bodyMarkdown?, bodyHtml? | `note.userId == currentUser` | no |
| `delete_note` | noteId, reason? | `note.userId == currentUser` | **yes** — a proposal that needs confirmation |
| `create_tag` | name, color? | N/A (new) | no |
| `delete_tag` | tagId, reason? | `tag.userId == currentUser` | **yes** — a proposal that needs confirmation |
| `write_adr` | slug, title, tags?, body | N/A (creates a file) | no — but it **fails** if the slug exists |
| `update_adr` | slug, status, supersededBy? | N/A (rewrites a file) | no — the body is never touched |
| `decompose_and_create` | title, description?, priority?, projectId?, tagIds? | N/A (new) | no |

**On "dry-run".** There is no `dryRun` field in these tools, and the four `delete_*`
tools do not delete: they create a **proposal** that the user confirms. A tool
description that says "permanently deletes" is wrong, and the catalogue used to say
exactly that.

## Common Mistakes

```kotlin
// ❌ WRONG — no authorization check before update
repo.update(task.copy(title = args.title))

// ✅ CORRECT — verify ownership, and report it in the output
val task = repo.watchById(TaskId(args.taskId)).first()
if (task == null || task.userId != currentUser.userId) {
    return Json.encodeToString(
        <Tool>Output.serializer(),
        <Tool>Output(error = "no task '${args.taskId}' for this user"),
    )
}

// ❌ WRONG — hardcoded dryRun=false for delete
val dryRun = false

// ✅ CORRECT — destructive tools default to dryRun=true
val dryRun = args.dryRun

// ❌ WRONG — returning the raw exception message: it can carry arguments and paths
return Result.failure(RuntimeException("SQL exception: $e")).toString()

// ✅ CORRECT — a short, actionable message in the output
return Json.encodeToString(
    <Tool>Output.serializer(),
    <Tool>Output(error = "could not create the task, try again"),
)

// ❌ WRONG — no pagination on list tools
return allItems.map { it.toSummary() }.toJson()

// ✅ CORRECT — cursor-based pagination
val page = items.drop(offset).take(PAGE_SIZE)
val nextCursor = if (items.size > offset + PAGE_SIZE) Cursor(offset + PAGE_SIZE, sortKey).encode() else null
```

## Related Skills

- `docs/architecture/mcp-server.md` — how tools are registered in the MCP server.
- `singularity-todo-ai-tool` — the base `SimpleTool<T>` pattern for all tools.
- `singularity-todo-llm-usage-tracking` — `UsageRecorder` injection into tools.
- `singularity-todo-multi-profile` — `currentUser.userId` and `profileId` for authorization.
- `singularity-todo-room-multi-instance` — concurrent write safety.
- ADR `2026-09-07-write-tools-in-koog-registry` — rationale for write-tools in the Koog registry.
