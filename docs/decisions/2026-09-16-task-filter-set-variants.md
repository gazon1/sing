---
title: "TaskFilter and Selector set variants: ByTags/ByPriorities/ByRegexp SQL-backed filters"
date: 2026-09-16
tags: [tasks, domain-model, sql, selector, mr2a]
---

## Context

После MR1 `TaskRepositoryImpl` имеет stub `ByStatuses -> flowOf(emptyList())` — фильтр работает in-memory через `AgendaEvaluator.matches`, но не через SQL. Это неэффективно на больших списках задач (тысячи tasks).

`Selector.Tag(TagId)` в MR1 — single-tag only. Multi-tag filtering (AND / ANY) отсутствует. MR1 ADR зафиксировал: "Set — MR2".

`Selector.Priorities` и `Selector.Regexp` уже существуют в `AgendaEvaluator.matches` (in-memory), но параллельные `TaskFilter` варианты не реализованы.

## Idea

Варианты:
1. **Оставить in-memory** — `AgendaEvaluator.matches` достаточно для большинства случаев. SQL-бенчмарк не проводился.
2. **Добавить SQL-фильтры** (этот ADR) — `watchByAnyTag`, `watchByAllTags`, `watchByPriorities`, `watchByRegexp` в `TaskDao`. Repository dispatch.

## Decision

### 1. `TaskFilter` — новые variants

```kotlin
sealed interface TaskFilter {
    data class ByTags(val ids: Set<TagId>, val matchAll: Boolean = false) : TaskFilter
    data class ByPriorities(val priorities: Set<TaskPriority>) : TaskFilter
    data class ByRegexp(val pattern: String) : TaskFilter
}
```

`matchAll = false` (ANY) по default — UX least surprise. `matchAll = true` — explicit AND semantics.

### 2. `Selector.Tag` → `Selector.Tags` с migration shim

```kotlin
@Deprecated("Use Tags(setOf(id)) — supported for one release via JSON shim")
@Serializable @SerialName("Tag") data class Tag(val id: TagId) : Selector

@Serializable @SerialName("Tags") data class Tags(val ids: Set<TagId>) : Selector
```

**Migration shim**: custom `KSerializer<Selector>` via `JsonContentPolymorphicSerializer` или `JsonTransformingDeserializer` — принимает оба `"Tag"` и `"Tags"` discriminator значения. Это позволяет:
- MR1 drafts / AI-tool history / backups с `"Tag": {"id": ...}` десериализуются корректно
- Новый код генерирует `"Tags": {"ids": [...]}` при сохранении
- Через 1 release cycle `Tag` можно удалить

### 3. `Selector.Regexp` — substring `LIKE`, не REGEXP

```kotlin
// AgendaEvaluator.matches — in-memory
is Selector.Regexp -> task.title.matches(Regex(selector.pattern, RegexOption.IGNORE_CASE))

// TaskFilter.ByRegexp — SQL
@Query("""
    SELECT * FROM tasks
    WHERE user_id = :userId AND archived_at IS NULL
      AND lower(title) LIKE lower('%' || :query || '%')
    ORDER BY due_date ASC, is_pinned DESC
""")
fun watchByRegexp(userId: String, query: String): Flow<List<TaskEntity>>
```

**Почему LIKE, не REGEXP**:
- `REGEXP` требует регистрации extension function в Room (per-driver callback)
- SQLite `REGEXP` engine (ICU) отличается от `kotlin.text.Regex` — разные результаты для `a.b`
- `LIKE '%term%'` — multiplatform, работает из коробки, Room parameter binding
- Семантика: "содержит подстроку" — достаточно для 95% use cases

### 4. `Selector.Tags` — `matchAll` semantics

| Query | SQL | Semantic |
|---|---|---|
| `matchAll = false` (ANY) | `DISTINCT + WHERE tag_id IN (:ids)` | Задача с любым из tag ids |
| `matchAll = true` (ALL) | `GROUP BY id HAVING COUNT(DISTINCT tag_id) = :size` | Задача со всеми tag ids |

### 5. Room queries (Daos.kt)

```kotlin
@Query("""
    SELECT DISTINCT t.* FROM tasks t
    INNER JOIN task_tags tt ON t.id = tt.task_id
    WHERE t.user_id = :userId AND t.archived_at IS NULL
      AND tt.tag_id IN (:tagIds)
    ORDER BY t.due_date ASC, t.is_pinned DESC
""")
fun watchByAnyTag(userId: String, tagIds: List<String>): Flow<List<TaskEntity>>

@Query("""
    SELECT t.* FROM tasks t
    INNER JOIN task_tags tt ON t.id = tt.task_id
    WHERE t.user_id = :userId AND t.archived_at IS NULL
      AND tt.tag_id IN (:tagIds)
    GROUP BY t.id
    HAVING COUNT(DISTINCT tt.tag_id) = :size
    ORDER BY t.due_date ASC, t.is_pinned DESC
""")
fun watchByAllTags(userId: String, tagIds: List<String>, size: Int): Flow<List<TaskEntity>>

@Query("""
    SELECT * FROM tasks
    WHERE user_id = :userId AND archived_at IS NULL
      AND priority IN (:priorities)
    ORDER BY due_date ASC, is_pinned DESC
""")
fun watchByPriorities(userId: String, priorities: List<String>): Flow<List<TaskEntity>>

@Query("""
    SELECT * FROM tasks
    WHERE user_id = :userId AND archived_at IS NULL
      AND lower(title) LIKE lower('%' || :query || '%')
    ORDER BY due_date ASC, is_pinned DESC
""")
fun watchByRegexp(userId: String, query: String): Flow<List<TaskEntity>>
```

**Room binding**: все queries принимают `List<String>`, не `Set<String>` — Room KMP не гарантирует `Set` binding. Конвертация `ids.map { it.value }.toList()` в repository.

### 6. `AgendaPresets` API

```kotlin
// Single-tag — остаётся (most common)
fun byTag(id: TagId): AgendaDefinition = byTags(setOf(id))

// Multi-tag
fun byTags(ids: Set<TagId>): AgendaDefinition = agenda("Tagged") {
    section("Tags", Selector.Tags(ids), order = 0)
}
```

### 7. MCP tool update

MCP `agenda_view` write tool обновляется для эмита `Tags(ids = setOf(tagId))` вместо `Tag(id = tagId)`. Backup format compatibility test: MR1 JSON → MR2a decoded → MR2a encoded → decoded fields match.

## Rationale

- **`ByTags` over `ByTag` (set vs single)** — future-proof; single-tag case trivially wraps set
- **Migration shim** — conservative; MR1 data in drafts/backups preserved without versioning overhead
- **LIKE over REGEXP** — simplicity wins; REGEXP engine mismatch is a silent correctness bug
- **ANY default for matchAll** — Taskwarrior UX; explicit `matchAll = true` for AND semantics

## Consequences

- `TaskRepositoryImpl.watchTasks` получает 4 новые dispatch branches
- `FakeTaskDao` и `FakeTaskRepository` mirror для всех 4 новых queries
- `AgendaEvaluator.matches` обновлён для `Selector.Tags` (список tags → `task.tags.any { it in ids }`)
- **Breaking**: `Selector.Tag` rename — `AgendaPresetsTest` JSON snapshots обновляются
- **Breaking**: MCP tool producer-side обновляется

## Links

- MR1 ADR: `2026-09-16-agenda-engine.md`
- TaskFilter ADR: `2026-09-16-task-list-filter-to-task-status.md`
- Skills: `singularity-todo-feature-scaffold`, `singularity-todo-stable-json`, `singularity-todo-room-migration`, `singularity-todo-mcp-server`
