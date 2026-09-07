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
        ?: return McpToolError.NotFound("task", args.id).format(isError = true)

    // Authorization check
    if (existing.userId != currentUser.userId) {
        return McpToolError.Unauthorized("task:${args.id}").format(isError = true)
    }

    // Proceed with mutation...
}

private val currentUser: CurrentUser  // injected
```

**Rule:** authorization errors return `isError = true` with actionable text — **never** throw or return HTTP-style codes. The LLM reads the message and self-corrects.

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

```kotlin
// Every tool execute() returns either:
//  1. Success → JSON string of Output
//  2. isError=true → JSON string with actionable text
//  3. Internal error → throw McpInternalException → JSON-RPC -32603

override suspend fun execute(args: <Tool>Input): String {
    return runCatching {
        doExecute(args)
    }.fold(
        onSuccess = { it.toJson() },
        onFailure = { error ->
            when (error) {
                is ValidationException -> McpToolError.Validation(field, error.message).format(isError = true)
                is NotFoundException -> McpToolError.NotFound(resource, id).format(isError = true)
                is UnauthorizedException -> McpToolError.Unauthorized(resource).format(isError = true)
                else -> throw error  // re-throw for JSON-RPC -32603
            }
        }
    )
}
```

## Tool Annotation Convention

Every write tool must have the correct annotation in `ToolAnnotations.kt`:

| Tool | Annotation |
|---|---|
| `tasks.create` | `idempotentHint = true` |
| `tasks.update` | `idempotentHint = true` |
| `tasks.complete` | `idempotentHint = true` |
| `tasks.delete` | `destructiveHint = true` |
| `tasks.restore` | `destructiveHint = true` (restore from trash) |
| `tasks.pin` | none |
| `tasks.set_priority` | none |
| `tasks.set_due_date` | none |
| `tasks.move_to_project` | none |
| `projects.create` | `idempotentHint = true` |
| `projects.delete` | `destructiveHint = true` |
| `notes.create` | `idempotentHint = true` |
| `notes.archive` | `destructiveHint = true` |
| `notes.pin` | none |
| `tags.create` | `idempotentHint = true` |
| `tags.assign` | none |
| `adr.write` | `openWorldHint = true` (creates external file) |
| `decompose_and_create` | none |

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

| Tool | Input | Authorization | Dry-run default |
|---|---|---|---|
| `tasks.create` | title, description?, priority?, projectId?, tagIds?, dueDate? | N/A (new) | `false` |
| `tasks.update` | taskId, title?, description?, priority?, projectId?, dueDate? | `task.userId == currentUser` | `false` |
| `tasks.complete` | taskId, completed: Boolean | `task.userId == currentUser` | `false` |
| `tasks.delete` | taskId | `task.userId == currentUser` | `true` |
| `tasks.restore` | taskId | `task.userId == currentUser` | `true` |
| `tasks.pin` | taskId, pinned: Boolean | `task.userId == currentUser` | `false` |
| `tasks.set_priority` | taskId, priority: Int (0-4) | `task.userId == currentUser` | `false` |
| `tasks.set_due_date` | taskId, dueDate: String? (ISO-8601) | `task.userId == currentUser` | `false` |
| `tasks.move_to_project` | taskId, projectId: String? | `task.userId == currentUser` | `false` |
| `projects.create` | name, description?, color?, icon? | N/A (new) | `false` |
| `projects.delete` | projectId | `project.userId == currentUser` | `true` |
| `notes.create` | title, bodyMarkdown, color?, folder? | N/A (new) | `false` |
| `notes.archive` | noteId | `note.userId == currentUser` | `true` |
| `notes.pin` | noteId, pinned: Boolean | `note.userId == currentUser` | `false` |
| `tags.create` | name, color? | N/A (new) | `false` |
| `tags.assign` | taskId, tagId | `task.userId == currentUser` | `false` |
| `adr.write` | title, slug, context, decision, rationale, consequences?, createNote: Boolean | N/A | `false` |
| `decompose_and_create` | taskId | `task.userId == currentUser` | `false` |
| `checklist.complete_item` | itemId, completed: Boolean | `item.userId == currentUser` | `false` |

## Common Mistakes

```kotlin
// ❌ WRONG — no authorization check before update
repo.update(task.copy(title = args.title))

// ✅ CORRECT — verify ownership
val task = repo.watchById(TaskId(args.taskId)).first()
    ?: return McpToolError.NotFound("task", args.taskId).format(isError = true)
if (task.userId != currentUser.userId) 
    return McpToolError.Unauthorized("task:${args.taskId}").format(isError = true)

// ❌ WRONG — hardcoded dryRun=false for delete
val dryRun = false

// ✅ CORRECT — destructive tools default to dryRun=true
val dryRun = args.dryRun

// ❌ WRONG — returning raw exception message
return Result.failure(RuntimeException("SQL exception: $e")).toString()

// ✅ CORRECT — actionable error text
return McpToolError.Internal("Database error during create", e).format(isError = true)

// ❌ WRONG — no pagination on list tools
return allItems.map { it.toSummary() }.toJson()

// ✅ CORRECT — cursor-based pagination
val page = items.drop(offset).take(PAGE_SIZE)
val nextCursor = if (items.size > offset + PAGE_SIZE) Cursor(offset + PAGE_SIZE, sortKey).encode() else null
```

## Related Skills

- `singularity-todo-mcp-server` — how tools are registered in the MCP server.
- `singularity-todo-ai-tool` — the base `SimpleTool<T>` pattern for all tools.
- `singularity-todo-llm-usage-tracking` — `UsageRecorder` injection into tools.
- `singularity-todo-multi-profile` — `currentUser.userId` and `profileId` for authorization.
- `singularity-todo-room-multi-instance` — concurrent write safety.
- ADR `2026-09-07-write-tools-in-koog-registry` — rationale for write-tools in the Koog registry.
