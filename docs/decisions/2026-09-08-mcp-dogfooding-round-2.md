---
title: "MCP dogfooding — round 2 plan index"
date: 2026-09-08
tags: ["mcp", "dogfooding", "round-2", "followups"]
---

## Idea
Round 1 MCP dogfooding (C1..C7) завершился, 31 tool, 2 ADR, skill, top-task с parent_task_id. Назрели следующие 5 улучшений; это карта-указатель на них.

## Decision
Пять followup-планов заведены в БД через MCP (round 2 driver), все привязаны к ai-agent профилю:
  - **mcp-ux**: list_tasks default userId + sub-task hierarchy в UI
  - **ui-subtask**: реальный UI для parent_task_id (TasksScreen, TaskEditorScreen)
  - **ai-tooling**: idempotency / dryRun contract audit на 5 entities
  - **mcp-policy**: ADR 'когда MCP недоступен vs tool failed'
  - **refactor**: разделить :mcp-server на чистые компоненты + in-process тесты

## Rationale
- Round 1 дал 31 работающий tool + parent_task_id — но UI их не использует.
- list_tasks возвращает [] на свежесозданные таски потому что default userId='local-user' ≠ реальный userId. Это самый надоедливый из 5 issue.
- 5 plans = 5 разных категорий (UX, UI, контракт, policy, рефакторинг). Агент между сессиями может работать над любым параллельно.

## Consequences
- В профиле ai-agent теперь 5 top-level plans × ~6 sub-tasks = ~30 новых rows.
- tag 'mcp-ux'/'ui-subtask'/'ai-tooling'/'mcp-policy'/'refactor' — 5 persistent categories для фильтрации.
- Каждый plan имеет parentTaskId = top-task; UI должен теперь уметь их показать (см. plan 'ui-subtask').
