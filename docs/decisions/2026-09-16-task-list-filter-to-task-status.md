---
title: "Rename TaskListFilter → TaskStatus: domain-level completion status enum"
date: 2026-09-16
tags: [tasks, domain-model, rename]
status: accepted
---

## Context

В ходе проектирования AgendaEngine выяснилось, что `TaskListFilter` — presentation-layer enum (`ALL / ACTIVE / COMPLETED`) — используется как для UI-чипов фильтрации, так и как доменный концепт в будущем `AgendaSelector.Statuses(Set<TaskStatus>)`.

Два слоя используют одно имя для разных вещей: presentation слой — `TaskListFilter.ALL` (показывать все), domain/DSL слой — `TaskStatus.Active` (только незавершённые).

## Idea

Варианты:
1. **Оставить `TaskListFilter` как есть** — presentation enum, DSL определяет свой `TaskStatus` отдельно. Дублирование имён, путаница.
2. **Удалить `TaskListFilter`, переиспользовать `TaskStatus` everywhere** — единый источник истины для completion status во всех слоях. Требует rename в call-sites.
3. **Выделить `TaskStatus` в domain/model как единый тип** (этот ADR) — `TaskListFilter` удаляется, `TaskStatus` живёт в `domain/model/`, используется и в UI, и в DSL.

## Decision

Удалить `enum class TaskListFilter` из `feature/tasks/presentation/model/TaskUi.kt`.
Создать `enum class TaskStatus { All, Active, Completed }` в `feature/tasks/domain/model/TaskStatus.kt`.

Все call-sites обновлены:
- `TasksViewModel` (`TaskList.kt`): `_statusFilter: MutableStateFlow(TaskStatus.All)`
- `TaskFilterChips.kt`: параметры `selected: TaskStatus`, `onSelect: (TaskStatus) -> Unit`
- `TaskListScreen.kt`: `counts: Map<TaskStatus, Int>`

`CalendarTaskStatus` — **не трогаем**, это отдельный тип для календарного рендеринга (PENDING / DONE / OVERDUE).

## Rationale

- **Единый источник истины**: completion status — доменная концепция, не UI-концепция. Три значения (`All / Active / Completed`) покрывают и UI-чипы, и DSL-селектор.
- **`All` vs `Active`**: `All` означает «без фильтра по статусу» (показывать все), `Active` — «только незавершённые». Это разные семантики, обе нужны.
- **`CalendarTaskStatus` не конфликтует**: PENDING/DONE/OVERDUE — это derived status с учётом dueDate, а не raw completion. Другая размерность.

## Consequences

- `TaskListFilter` удалён — поиск по коду вернёт 0 результатов (если кто-то добавил вручную после этого коммита — это регресс).
- `TaskStatus` в domain/model доступен для AgendaEngine DSL без добавления cross-layer импорта.
- `@Serializable` на `TaskStatus` — нужен для kotlinx.serialization AgendaDefinition (saved views в будущем).

## Links

- Commit: `refactor(tasks): rename TaskListFilter → TaskStatus, move to domain/model`
- `feature/tasks/domain/model/TaskStatus.kt` — новый файл
- `feature/tasks/presentation/model/TaskUi.kt` — `enum TaskListFilter` удалён
- `feature/agenda/` — AgendaEngine (следующий MR)
