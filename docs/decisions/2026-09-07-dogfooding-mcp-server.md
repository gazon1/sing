---
title: Dogfooding MCP Server — ZCode Agent управляет задачами через stdio
date: 2026-09-07
status: accepted
tags: [mcp, dogfooding, koog, agent]
---

# Dogfooding MCP Server

## Context

ZCode CLI / Claude Code / Cursor может подключаться к MCP-серверу через stdio. Нам нужно, чтобы AI-агент из терминала мог управлять задачами, проектами, заметками, тегами и ADR в Singularity Todo — без UI, через тот же KMP код, который использует app.

Текущие ограничения:
- AI-фичи (refine_task, decompose_task и т.п.) работают только внутри запущенного приложения
- Нет способа управлять задачами из терминала программно
- Нет изоляции AI-агентских данных от пользовательских

## Decision

Создать новый JVM-модуль `:mcp-server` (не `:cli`), который:
- Подключается к Room KMP DB (single source of truth, НЕ xerial:sqlite-jdbc)
- Экспортирует 17+ write-tools + read-tools через Koog `ToolRegistry`
- Использует stdio transport из `io.modelcontextprotocol:kotlin-sdk:0.15.0`
- Поддерживает two-tier error model (business → `isError:true`, internal → `-32603`)
- Принимает `--profile=ai-agent` для изоляции AI-агентских данных

```
mcp-server/src/main/kotlin/com/singularity/todo/mcp/
  Main.kt                  — stdio transport, JSON-RPC dispatch
  ToolRegistrar.kt         — Koog SimpleTool → MCP Tool adapter
  ToolAnnotations.kt       — readOnly/destructive/idempotent/openWorld
  errors/
    McpToolError.kt       — Validation/NotFound/Conflict/Unauthorized/Internal
    ErrorMapper.kt         — → JSON-RPC error response
  pagination/
    CursorCodec.kt        — URL-safe Base64 cursor
```

## Rationale

- **JVM-модуль (не CLI)** — переиспользует Room-драйвер из shared, не нужен отдельный процесс БД
- **Koog ToolRegistry** — единый registry для всех tools; MCP adapter merely translates to MCP protocol
- **stdio transport** — работает с ZCode, Claude Code, Cursor без HTTP-сервера
- **Room из shared** — single source of truth, миграции работают автоматически
- **Two-tier errors** — AI-агенту важно отличать "задача не найдена" (retry) от "баг" (stop)

## Consequences

- Новый Gradle-модуль `:mcp-server` с dependency на shared
- AI-агенты получают нативный доступ к данным без UI
- Dogfooding-профиль "AI Agent" (🤖) изолирует агентские задачи от пользовательских
- Все token usage пишется в `llm_usage` с `profile_id=ai-agent`
- ZCode подключается через `mcpServers.singularity-todo` в настройках

## Links

- MCP SDK: `io.modelcontextprotocol:kotlin-sdk:0.15.0`
- Koog SimpleTool: `ai.koog.agents.core.tools.SimpleTool`
- Related: `2026-09-07-write-tools-in-koog-registry.md`, `2026-09-07-mcp-tool-error-model.md`
