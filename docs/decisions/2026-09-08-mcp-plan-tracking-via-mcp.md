---
title: "MCP plan tracking end-to-end"
date: 2026-09-08
tags: [mcp, dogfooding, plan-tracking]
status: accepted
---

## Idea
Проверить, что через MCP можно полноценно вести план: верхнеуровневая задача, декомпозиция, sub-task'и, ADR — всё через stdio в одном JVM-процессе.

## Decision
Один драйвер на Python гоняет JVM mcp-server через stdin/stdout JSON-RPC. На каждом раунде — отдельная JVM-инвокация (т.к. сервер закрывается на EOF stdin).

## Rationale
- `decompose_task` возвращает заголовки, но сам не пишет в DB — sub-task'и создаём через `create_task` в цикле.
- Связь sub ↔ parent пока через общий `projectId` и `tagIds` (parentTaskId появится в Task entity позже).
- `color` в `create_project`/`create_tag` сейчас Int; hex-строки станут поддерживаться после C2.

## Consequences
- Один прогон драйвера = реальная multi-step демонстрация MCP.
- ADR пишется в `docs/decisions/{YYYY-MM-DD}-{slug}.md` (server-side date).
- При недоступности LLM в драйвере зашит fallback sub-task'ов.
