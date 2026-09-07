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

**Tier 1 — Business errors** (agent-readable, `isError: true` в JSON-RPC `tool result`):
```kotlin
sealed class McpToolError {
    data class Validation(val field: String, val message: String)    // -42001
    data class NotFound(val entity: String, val id: String)          // -42002
    data class Conflict(val entity: String, val id: String, val reason: String) // -42003
    data class Unauthorized(val reason: String)                       // -42004
    data class Forbidden(val reason: String)                         // -42005
    data class ValidationResult(val errors: List<FieldError>)       // -42006, many errors
}
```

**Tier 2 — Internal errors** (agent should stop and report, `-32603`):
```kotlin
data class InternalError(val cause: String, val stackTrace: String? = null)
```

**Error mapper** — преобразует Result<T> в JSON-RPC response:
```kotlin
object ErrorMapper {
    fun toJsonRpcError(result: Result<*>): JsonRpcError {
        return when (val e = result.exceptionOrNull()) {
            is McpToolError.Validation     -> JsonRpcError(-42001, "Validation failed: ${e.message}", e.toMap())
            is McpToolError.NotFound       -> JsonRpcError(-42002, "${e.entity} not found: ${e.id}", e.toMap())
            is McpToolError.Conflict       -> JsonRpcError(-42003, "Conflict: ${e.reason}", e.toMap())
            is McpToolError.Unauthorized   -> JsonRpcError(-42004, e.reason, e.toMap())
            is McpToolError.Forbidden      -> JsonRpcError(-42005, e.reason, e.toMap())
            is McpToolError.ValidationResult -> JsonRpcError(-42006, "Validation failed", e.toMap())
            is McpToolError.Internal      -> JsonRpcError(-32603, "Internal error", e.toMap())
            else -> JsonRpcError(-32603, "Unknown error", null)
        }
    }
}
```

**MCP JSON-RPC response for tools:**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      { "type": "text", "text": "{\"isError\": true, \"error\": {\"code\": -42002, \"entity\": \"Task\", \"id\": \"t123\"}}" }
    ]
  }
}
```

## Rationale

- **Business errors как `isError: true`** — агенты могут retry или fallback для 4xx
- **Internal errors как `-32603`** — стандарт JSON-RPC, агенты останавливаются и репортают
- **Negative codes <-32000** — зарезервированы для tool errors, не конфликтуют с std JSON-RPC codes
- **Error data в `content[0].text`** — MCP tool result всегда `text`, error details сериализуются в JSON

## Consequences

- `McpToolError.kt` в `feature/ai/mcp/errors/`
- `ErrorMapper.kt` преобразует `Result<T>` в `JsonRpcError`
- Все write-tools используют `Result<T>` и `mapCatching` для internal errors
- AI-агент парсит `isError: true` из result text для business errors

## Links

- JSON-RPC 2.0 spec: https://www.jsonrpc.org/specification
- MCP spec 2025-06-18: tool error handling
- Related: `2026-09-07-dogfooding-mcp-server.md`, `2026-09-07-write-tools-in-koog-registry.md`
