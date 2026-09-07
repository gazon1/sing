---
title: Write Tools — idempotent контракт, dryRun, error model
date: 2026-09-07
status: accepted
tags: [mcp, tools, koog, idempotency]
---

# Write Tools in Koog Registry

## Context

MCP-сервер экспортирует 17+ tools. Среди них write-tools: `create_task`, `update_task`, `delete_task`, `create_note`, `update_note`, `delete_note`, `create_project`, `update_project`, `create_tag`, `delete_tag`, `write_adr`.

Проблемы, которые нужно решить:
1. **Idempotency** — повторный `create_task` с тем же `idempotency_key` должен возвращать существующую задачу, а не создавать дубликат
2. **Dry-run mode** — AI-агенты хотят preview перед применением
3. **Authorization** — какой профиль / userId использует агент?
4. **Error classification** — Validation vs NotFound vs Conflict vs Internal

## Decision

Каждый write-tool — `SimpleTool<Input>` с execute, returning `String` (JSON):

```kotlin
class CreateTaskTool(
    private val taskRepository: TaskRepository,
    private val profileAwareUser: ProfileAwareCurrentUser,
) : SimpleTool<CreateTaskTool.Input>(TypeToken.of(Input::class.java)) {

    data class Input(
        val title: String,
        val projectId: String? = null,
        val tagIds: List<String> = emptyList(),
        val dueAt: Long? = null,
        val priority: Int? = null,
        val idempotencyKey: String? = null,   // NEW
        val dryRun: Boolean = false,           // NEW
    )

    override fun execute(input: Input, meta: RequestMetadata): String {
        val userId = profileAwareUser.scopedUserId.value
        if (input.dryRun) {
            return encode(Result.success(Task.createDraft(input.title, userId)))
        }
        // idempotency: check existing by idempotency_key
        if (input.idempotencyKey != null) {
            val existing = taskRepository.findByIdempotencyKey(input.idempotencyKey, userId)
            if (existing != null) return encode(Result.success(existing))
        }
        val task = Task.create(...)
        taskRepository.create(task)
        return encode(Result.success(task))
    }
}
```

**Tool annotations** на каждом tool:
- `@ToolAnnotation(readOnly = false, destructive = false, idempotent = true)` — create
- `@ToolAnnotation(readOnly = false, destructive = true, idempotent = false)` — delete
- `@ToolAnnotation(readOnly = false, destructive = false, idempotent = true)` — update

**Idempotency key storage:** `TaskEntity.idempotency_key` (unique index, nullable).

**Dry-run:** возвращает preview задачи без записи в БД.

## Rationale

- **Idempotency key** — AI-агент может retry без побочных эффектов; основано на GitHub API паттерне
- **Dry-run** — позволяет агенту проверить результат перед commit (критично для автономных агентов)
- **Scoped userId** — `ProfileAwareCurrentUser` гарантирует, что агент пишет в правильный профиль
- **Tool annotations** — MCP spec 2025-06-18 требует `readOnly/destructive/idempotent/openWorld` для tool classification

## Consequences

- `TaskRepository` получает `findByIdempotencyKey(key, userId)` метод
- `TaskEntity` получает `@ColumnInfo("idempotency_key") val idempotencyKey: String?`
- Auto-migration v9 добавляет unique index на `(idempotency_key, user_id)` where not null
- Все 17+ tools следуют этому контракту

## Links

- MCP spec: `readOnly`, `destructive`, `idempotent`, `openWorld` annotations
- Koog SimpleTool: `ai.koog.agents.core.tools.SimpleTool`
- Related: `2026-09-07-dogfooding-mcp-server.md`, `2026-09-07-mcp-tool-error-model.md`
