---
title: "Org-mode architectural lessons: cascade, visitor, computed, super-agenda"
date: 2026-09-17
tags: [architecture]
---

## Context

Singularity Todo уже имеет ряд pure-domain решений, формально близких к идеям Org-mode:

- `AgendaEvaluator` (`feature/agenda/domain/logic/AgendaEvaluator.kt:27`) — pure секционный движок, концептуально идентичный `org-super-agenda`: external filter → sections with `:discard`-семантикой.
- `Selector` (`feature/agenda/domain/model/Selector.kt`) — sealed predicate с `AllOf` / `AnyOf` / `Not` композицией, аналог super-agenda boolean logic.
- `Task.isCompleted` / `isTrashed` (`feature/tasks/domain/model/Task.kt:110-111`) — корректное разделение parsed vs derived state.
- `assertNoNesting` (`feature/tasks/domain/TaskDomain.kt:188`) — 1-level иерархия, enforced в domain layer.
- `parentTaskId` (`Task.kt:98`) + `Project.parentId` — flat AST с backref, как Org-mode outline.

Однако ряд архитектурных приёмов Org-mode отсутствует или реализован ad-hoc:

1. **Дубликат predicates.** Логика «overdue» живёт минимум в трёх местах: `AgendaEvaluator.matches:71-73`, `Selector.Overdue:119-121`, `TaskDomain.matchesFilter:169-181`. Любая правка требует синхронных изменений.
2. **Отсутствует cascade.** `Task.projectId → Project.tags` не каскадирует, `Task.parentTaskId` не используется для property inheritance. `Selector.Tag` матчит только собственные теги задачи.
3. **Отсутствует visitor.** Рекурсивные `.filter { … }.map { … }` рассыпаны по слою (`TaskRepositoryImpl.kt:116`, `AgendaContent.kt:227-228`).
4. **Super-agenda transformers.** `Selector` — только predicate; `AgendaBadge` (badge layer) вычисляется отдельно в `AgendaEvaluator.computeBadge`. Нет единого compose-пайплайна `select → transform → render`.

## Idea

Изучены четыре варианта внедрения архитектурных приёмов Org-mode в KMP-проект:

1. **Оставить как есть.** Дубликаты остаются, расширение требует синхронных правок. Просто, но не масштабируется.
2. **Прямой порт Elisp-подходов.** Plist-like `Map<String, Any>` контейнеры, weak references. Снижает type-safety, не использует преимущества Kotlin.
3. **Pure infrastructure modules** (этот ADR): три новых модуля `core/tree/`, `feature/tasks/domain/logic/Computed.kt`, расширение `Selector` до super-agenda. Без миграций Room.
4. **Полная перепись Task-домена под AST.** Разворачивание `Task` в полное `sealed interface OrgNode`. Большой объём, затрагивает Room-схему, не даёт пропорциональной пользы на текущей 1-level иерархии.

## Decision

Внедряем **вариант 3**: pure infrastructure слой из четырёх частей. Никакой миграции Room, никаких изменений публичного API кроме добавления extension-функций.

### Архитектура (4 модуля)

#### 1. `core/tree/Cascade.kt` — generic cascade через bottom-up walk

Реализует приём `org-element-property-inherited`: поиск ближайшего non-nil значения по цепочке ancestors.

```kotlin
inline fun <T, K, V> List<T>.cascadeUp(
    keyOf: (T) -> K,
    parentOf: (T) -> K?,
    all: List<T>,
    extract: (T) -> V?,
    stopOn: (V) -> Boolean = { false },
): V?
```

Применения:
- `Task.cascadeProjectColor(allProjects)` — проект может наследовать цвет родителя.
- `Task.cascadeDefaultTags(allProjects)` — теги проекта как default для задачи.
- `AgendaEvaluator.matches` — новый флаг `inheritTags` в `Selector.Tags` (соответствует `org-agenda-use-tag-inheritance`).

Memoization — `Map<TaskId, V>` с invalidation по `updatedAt` (поле уже есть на `Task` и `SyncColumns`).

#### 2. `core/tree/TreeVisitor.kt` — depth-first visitor с директивами

Аналог `org-element-map` с фильтрацией по типу и early-stop:

```kotlin
sealed interface TraversalDirective<T> {
    data class Continue<T>(val value: T) : TraversalDirective<T>
    data class SkipSubtree<T>(val value: T) : TraversalDirective<T>
    data object Stop : TraversalDirective<Nothing>
}

fun <T> List<T>.traverseDepthFirst(
    childrenOf: (T) -> List<T>,
    visit: (T, depth: Int) -> TraversalDirective<Unit>,
)
```

Применения:
- `TaskRepositoryImpl.listSubtree(parent, allTasks)` с `SkipSubtree` если count уже > threshold.
- `AgendaContent` — заменить `indentLevel = if (task.parentTaskId != null) 1 else 0` на visitor.
- `ProjectsViewModel` — render дерева проектов (уже 1-level `parentId`) с lazy expand.

#### 3. `feature/tasks/domain/logic/Computed.kt` — единый источник derived predicates

```kotlin
val Task.isOverdue: Boolean
    get() = dueDate != null && dueDate < Today.value && !isCompleted && !isTrashed

val Task.isReady: Boolean
    get() = !isCompleted && !isTrashed && /* нет open subtasks */

val Task.isBlocked: Boolean
    get() = /* future: dependsOn.isNotEmpty() && !dependencies.all { it.isCompleted } */
```

`AgendaEvaluator.matches`, `Selector.Overdue`, `TaskDomain.matchesFilter`, `AgendaEvaluator.computeBadge` — все три места переходят на `task.isOverdue` / `task.isReady`. Правило: **computed state не дублируется inline нигде, кроме `Computed.kt`**.

`Today.value` — реактивная ссылка на `todayFlow(clock)`, который уже запланирован в `2026-09-16-agenda-engine.md` как MR2.

#### 4. Расширение `Selector` до super-agenda

Текущий `Selector` — только predicate. Добавляются две сущности, не ломающие существующий контракт:

```kotlin
// super-agenda :transformer
fun interface SelectorTransformer {
    fun apply(task: Task, today: LocalDate): AgendaBadge?
}

data class AgendaDefinition(
    val sections: List<Section>,
    val transformers: List<SelectorTransformer> = emptyList(),
    // ...
)
```

Применение: `AgendaEvaluator.computeBadge` становится `definition.transformers.fold(task) { badge, t -> t.apply(task, today) ?: badge }`. Это позволяет external config (например, "AI-suggested priority badge") подключаться без правки evaluator.

`AutoGrouping` (аналог `:auto-priority` в super-agenda) — отдельный sealed variant `AgendaLayout.AutoGroup(field: AutoField)`; не в этом ADR.

### Чего НЕ переносим из Org-mode

- **Plain-text хранение.** Singularity остаётся на Room — `2026-09-07-backup-directory-via-koin-string` уже зафиксировал backup как JSON+ZIP. Markdown-экспорт — отдельный возможный escape-hatch, не часть этого решения.
- **N-level outline в tasks.** `assertNoNesting` остаётся. Pure infrastructure готовит фундамент, но N-level — это отдельная миграция Room с `AutoMigration` (skill `singularity-todo-room-migration`).
- **LOGBOOK, Repeater, WAITING state, Property drawers.** Отдельные фичи, не pure infrastructure. Каждая — свой ADR с feature-impact.
- **Hooks как OS-скрипты.** В KMP-проекте hooks = `Flow<DomainEvent>` через reactive bus (`P1.1` в roadmap) — это отдельный ADR, не pure infrastructure.

## Rationale

- **Минимальный риск.** Все четыре модуля — additive: новые файлы + extension-функции. Никаких изменений сигнатур существующих классов, никаких Room-миграций, никаких breaking changes в публичном API.
- **Type-safety > Elisp.** Где Org-mode использует plists + symbols + dynamic dispatch, Kotlin получает sealed interfaces + exhaustive `when` + value classes. Это строже, чем оригинал.
- **Pure-инвариант уже есть.** `AgendaEvaluator.evaluate` и `TaskDomain.matchesFilter` уже pure; расширение следует тому же правилу. Side effects (badge updates, recomposition triggers) — на стороне VM, как уже зафиксировано в `2026-09-15-viewmodel-state-ownership`.
- **Маленький спринт, большой эффект.** Удаление трёх дубликатов `isOverdue` — немедленное снижение bug-surface. Cascade + visitor открывают дверь для будущих фич (Column view, SubtreeStats) без повторного прохода по ad-hoc местам.
- **Делает super-agenda композицию testable.** Transformers — это pure `(Task, today) → AgendaBadge?`, легко покрываются `commonTest` без Compose и Koin.

## Consequences

- **All** new pure-domain code lives under `core/tree/` or `feature/<x>/domain/logic/` and must be pure (no `runBlocking`, no Compose imports, no Koin). _(from `2026-09-17-orgmode-architectural-lessons`)_
- **Always** route derived predicates (`isOverdue`, `isReady`, `isBlocked`) through `feature/tasks/domain/logic/Computed.kt`. Never duplicate inline in `AgendaEvaluator`, `Selector`, or `TaskDomain.matchesFilter`. _(from `2026-09-17-orgmode-architectural-lessons`)_
- **Always** use `core/tree/Cascade.kt` `cascadeUp` for inheritance queries; never walk ancestors ad-hoc with `find { it.parentId == ... }` chains. _(from `2026-09-17-orgmode-architectural-lessons`)_
- **Always** use `core/tree/TreeVisitor.kt` `traverseDepthFirst` for recursive tree operations; never write recursive `.filter { … }.map { … }` chains. _(from `2026-09-17-orgmode-architectural-lessons`)_
- **Always** keep `Selector` a pure predicate; badge/transform logic belongs to `SelectorTransformer` attached to `AgendaDefinition`, not embedded in evaluator. _(from `2026-09-17-orgmode-architectural-lessons`)_
- **Never** store derived predicates in Room (`isOverdue`, `isReady`). They are computed on read via extension properties. _(from `2026-09-17-orgmode-architectural-lessons`)_
- **Never** migrate to plain-text file storage for tasks. Room remains the source of truth; markdown export (if added later) is a read-only projection. _(from `2026-09-17-orgmode-architectural-lessons`)_
- **Never** relax `assertNoNesting` without a separate ADR. N-level outline requires Room `AutoMigration` (skill `singularity-todo-room-migration`). _(from `2026-09-17-orgmode-architectural-lessons`)_
- Each pure-infrastructure module ships with at least one `commonTest` covering empty list, single element, deep nesting, and cycle detection. _(from `2026-09-17-orgmode-architectural-lessons`)_
- This ADR supersedes nothing; it layers new pure infrastructure over `2026-09-16-agenda-engine.md` and `2026-09-08-task-1-level-subtasks.md`. _(from `2026-09-17-orgmode-architectural-lessons`)_

## Implementation roadmap (separate PRs, separate ADRs as needed)

| Sprint | Deliverable | New ADR? |
|---|---|---|
| **A** | `core/tree/Cascade.kt` + `core/tree/TreeVisitor.kt` + tests | This ADR (creates the modules) |
| **B** | `feature/tasks/domain/logic/Computed.kt` + remove 3 duplications | Small ADR if public API expands |
| **C** | `SelectorTransformer` + `AgendaDefinition.transformers` | Small ADR; touches AgendaEngine public DSL |
| **D** | `AgendaEngine` consumes transformers; `AutoGroup` (optional) | Subset of `2026-09-16-agenda-engine.md` MR2 |

Each sprint produces a commit + digest refresh. Skipping a sprint is allowed if the previous one provides enough value on its own.

## Links

- Skill: `singularity-todo-decisions-workflow` — формат ADR
- Skill: `singularity-todo-pure-formatters` — соседний pure-инфра слой
- Skill: `singularity-todo-quality-tools` — kover gate для новых pure-модулей
- ADR: `2026-09-16-agenda-engine.md` — база для super-agenda расширения
- ADR: `2026-09-08-task-1-level-subtasks.md` — почему `assertNoNesting` остаётся
- ADR: `2026-09-15-viewmodel-state-ownership.md` — pure-domain ↔ side-effect граница
- ADR: `2026-09-07-backup-directory-via-koin-string.md` — почему не plain-text
- Code: `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/Task.kt` — Task с `isCompleted` / `isTrashed`
- Code: `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/TaskDomain.kt` — `matchesFilter` (одно из мест дубликата `isOverdue`)
- Code: `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/logic/AgendaEvaluator.kt` — `computeBadge` (второе место дубликата)
