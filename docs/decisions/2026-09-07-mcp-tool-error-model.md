---
title: MCP Tool Error Model — two-tier, JSON-RPC compatible
date: 2026-09-07
status: accepted
tags: [mcp, error-handling, json-rpc]
---

# MCP Tool Error Model

## Context

MCP-сервер должен возвращать ошибки в формате, понятном AI-агентам (Claude Code, ZCode, Cursor). JSON-RPC 2.0 определяет:
- `-32603 Internal Error` — баг сервера
- `-32602 Invalid Params` — плохой input
- `-32601 Method not found` — нет такого tool

Но для tool execution нужны business-level ошибки: "задача не найдена", "проект уже существует", "не авторизован".

## Decision

**Two-tier error model:**

**Tier 1 — Business errors** (agent-readable, returned as `CallToolResult(isError = true)`):
```kotlin
// mcp-server/src/main/kotlin/com/singularity/todo/mcp/errors/McpToolError.kt
sealed interface McpToolError {
    data class Validation(val field: String, val message: String) : McpToolError
    data class NotFound(val resource: String, val id: String) : McpToolError
    data class Conflict(val reason: String) : McpToolError
    data class Unauthorized(val resource: String) : McpToolError
}
```

The case classes contain exactly the fields the agent needs to self-correct: which field failed, which resource was missing, what the duplicate was about, or which resource is off-limits.

**Tier 2 — Internal errors** (agent should stop and report, JSON-RPC `-32603`):
```kotlin
sealed interface McpToolError {
    data class Internal(val message: String, val cause: Throwable? = null) : McpToolError
}
```

**Error mapper** — maps the sealed `McpToolError` to a `CallToolResult` or throws:
```kotlin
// mcp-server/src/main/kotlin/com/singularity/todo/mcp/errors/ErrorMapper.kt
object ErrorMapper {
    private const val INTERNAL_ERROR_CODE = -32603

    fun toResult(error: McpToolError): CallToolResult {
        return when (error) {
            is McpToolError.Internal -> throw McpException(
                code = INTERNAL_ERROR_CODE,
                message = error.format(),
                data = JsonNull,
                cause = error.cause,
            )
            is McpToolError.Validation,
            is McpToolError.NotFound,
            is McpToolError.Conflict,
            is McpToolError.Unauthorized -> CallToolResult(
                content = listOf(TextContent(text = error.format())),
                isError = true,
            )
        }
    }
}
```

**MCP tool result for business errors** (Tier 1):
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "isError": true,
    "content": [
      { "type": "text", "text": "[NotFound] Task 't123' not found" }
    ]
  }
}
```

**MCP error response for internal errors** (Tier 2):
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "error": {
    "code": -32603,
    "message": "[InternalError] <human-readable>"
  }
}
```

## Rationale

- **Business errors как `isError: true`** — агенты могут retry или fallback для 4xx
- **Internal errors как `-32603`** — стандарт JSON-RPC, агенты останавливаются и репортают
- **Tier 1 маппится на `CallToolResult` с `isError = true`** — это родная механика MCP SDK, не требует кастомного JSON-RPC error object
- **Tier 2 (Internal) бросает `McpException`** — SDK сериализует его в JSON-RPC error response автоматически
- **Tagged strings `[NotFound]`, `[Validation]`, ...** в `TextContent.text` — дают агенту моментальный grep-signal без парсинга JSON

## Consequences

- `McpToolError.kt` в `mcp-server/src/main/kotlin/com/singularity/todo/mcp/errors/`
- `ErrorMapper.kt` маппит `McpToolError` в `CallToolResult` или бросает `McpException`
- Все write-tools используют `Result<T>` + `mapCatching` для differentiation `Internal` от `Validation`/etc.
- AI-агент парсит `isError: true` из `result` для business errors и ловит `-32603` из `error` для internal

## Links

- JSON-RPC 2.0 spec: https://www.jsonrpc.org/specification
- MCP spec 2025-06-18: tool error handling
- Related: `2026-09-07-dogfooding-mcp-server.md`, `2026-09-07-write-tools-in-koog-registry.md`
