---
title: "Org-mode functional patterns: pure composition extensions"
date: 2026-09-17
tags: [architecture]
status: accepted
---

## Context

`2026-09-17-orgmode-architectural-lessons.md` зафиксировал четыре pure-infrastructure направления (`Cascade.kt`, `TreeVisitor.kt`, `Computed.kt`, `SelectorTransformer`) и roadmap спринтов A→D. Эти направления закрывают **архитектурные пробелы** (cascade, visitor, derived predicates, super-agenda compose), но не покрывают **функциональный стиль Org-mode**, на котором они основаны.

Org-mode построен на пяти устойчивых pure-function паттернах, применимых к Kotlin/KMP независимо от наличия Task-домена:

1. **Callback-driven traversal** (`org-element-map`): FUN не только фильтрует, но и управляет обходом (early-stop, skip-subtree).
2. **Pure-function cascade без stored values** (`org-entry-get`): нет denormalization, нет schema migrations при изменении иерархии.
3. **Immutable data + structural sharing** (cons cells в Org, `data class` в Kotlin): destructive операции выделены в отдельный API; pure path — default.
4. **Pipeline composition** (`org-agenda-list`): каждый шаг pure; failure в шаге → empty result, не exception.
5. **Homoiconic sealed hierarchy** (`org-element` AST = typed structure + predicate input + JSON): один тип — три роли, без параллельных DTO.

Каждый из этих паттернов уже частично применён в Singularity Todo, но без явного ADR они могут дрейфовать при будущих рефакторингах.

## Idea

Изучены три варианта оформления:

1. **Extension к существующему ADR.** Добавить секцию "Functional patterns" в `2026-09-17-orgmode-architectural-lessons.md`. Просто, но размывает фокус ADR-а (cascade+visitor+computed+super-agenda ≠ pure-function style).
2. **Новый отдельный ADR** (этот документ). Изолирует тему "functional style" от темы "infrastructure modules". Каждое направление — свой decision-log entry, по convention `singularity-todo-decisions-workflow`.
3. **Skill.** Превратить в `singularity-todo-pure-function-patterns` skill. Слишком жирно для одного skill'а — это всё-таки architectural rules, а не how-to.

## Decision

Создаём **отдельный ADR** (вариант 2). Парный к `2026-09-17-orgmode-architectural-lessons.md`, но с фокусом на pure-function style.

Этот ADR фиксирует пять pure-function приёмов Org-mode и расширяет roadmap двумя дополнительными pure-helper модулями: `Ancestors.kt` и `Bulk.kt`. Реализация откладывается — каждый pure-helper = отдельный коммит + digest refresh, как уже принято в ADR `2026-09-17-orgmode-architectural-lessons.md`.

### Mapping: Elisp pure-function → Kotlin

| Elisp pure-function | Kotlin-проекция | Где живёт (или будет жить) |
|---|---|---|
| `org-entry-get` (cascade lookup) | `cascadeUp(keyOf, parentOf, extract, stopOn)` | `core/tree/Cascade.kt` (ADR 17a, sprint A) |
| `org-element-map` (typed visitor) | `traverseDepthFirst(childrenOf, visit)` + `TraversalDirective.{Continue,SkipSubtree,Stop}` | `core/tree/TreeVisitor.kt` (ADR 17a, sprint A) |
| `org-element-extract-element` / `-copy` | `data class.copy()` (immutable) vs explicit destructive API | правило ниже |
| `org-element-lineage` | `fun T.ancestors(all: List<T>, parentOf: (T) -> K?): Sequence<T>` | `core/tree/Ancestors.kt` (новый, sprint A+) |
| `org-element-interpret-data` | round-trip `@Serializable` sealed hierarchy | `core/serialization/StableJson.kt` (уже есть) |
| `org-agenda-list` (multi-stage pipeline) | `AgendaDefinition` + `AgendaEvaluator.evaluate` | `feature/agenda/domain/logic/AgendaEvaluator.kt` |
| `org-super-agenda` (sections, discard, transformers) | `AgendaDefinition` + `Section(discard=true)` + `SelectorTransformer` | `feature/agenda/domain/model/AgendaDefinition.kt` |
| `org-search-view` (filter → agenda) | `AgendaPresets.searchLike(query)` | расширение `feature/agenda/domain/logic/AgendaPresets.kt` |
| `org-map-entries` (bulk transform) | `fun List<Task>.mapEntries(transform: (Task, today) -> Entry): List<Entry>` | `feature/tasks/domain/logic/Bulk.kt` (новый, sprint C+) |
| `org-archive-subtree` | `TaskRepository.archiveSubtree(rootId)` | **не в этом ADR** — фича, отдельный ADR |
| `org-refile` | `TaskRepository.move(...)` + `RefileTargetsCache` | **не в этом ADR** — фича, отдельный ADR |
| `org-element-property` (plookup) | generic `fun <T, V> T.attr(extractor, default): V` | `core/pure-formatters/Attr.kt` (новый, sprint C+) |

### Пять правил pure-function стиля

Каждое правило — single bullet для digest. Маркированы `**Always**` / `**Never**` где применимо.

#### R1. Callback управляет обходом, а не только фильтрует

`org-element-map` принимает FUN и анализирует его возвращаемое значение: спецсимвол `':early-exit` останавливает обход, `nil` означает "пропустить". Это сильнее filter+map: callback сам решает, продолжать ли спуск.

Kotlin-проекция: `TraversalDirective<T>` sealed interface (Continue | SkipSubtree | Stop). Уже в `TreeVisitor.kt` (ADR 17a, sprint A). Compile-time exhaustive `when` — строже, чем runtime check Elisp-символов.

**Правило**: новый код для обхода деревьев в `core/` и `feature/<x>/domain/` обязан использовать `traverseDepthFirst` или `cascadeUp`. Ad-hoc рекурсивные `find { … }` chains и `.filter { … }.map { … }` запрещены. _(covered by ADR 17a — `Always use core/tree/TreeVisitor.kt …`)_

#### R2. Cascade = pure function, не denormalized колонка

`org-entry-get` обходит ancestors заново на каждый вызов. Никакого `effectivePriority` stored-поля в схеме. Memoization — отдельная опциональная обёртка (text-property cache), не часть core API.

Это даёт два архитектурных бонуса:
- Изменение иерархии (новый parent) → inherited значения пересчитываются автоматически без миграции.
- Source of truth — `Task.parentTaskId` + `Project.parentId`, а не denormalized копия.

Kotlin-проекция: `cascadeUp(keyOf, parentOf, extract, stopOn)` — generic pure function. Уже в `Cascade.kt` (ADR 17a, sprint A).

**Правило**: derived значения (`effectivePriority`, `effectiveColor`, `effectiveTags`) — только как `fun Task.xxx(allProjects, allTasks): X` extension functions. Никогда как хранимое поле в `TaskEntity`. _(this ADR)_

#### R3. Immutable data + structural sharing = default

Org-mode AST — cons cell (immutable). Операции вроде `org-element-extract-element` мутируют `contents` родителя, но сами узлы не копируются. Kotlin `data class` через `copy()` даёт то же самое безопаснее: full immutability по умолчанию.

В Singularity это уже соблюдается: `Task`, `Project`, `Tag`, `AgendaDefinition`, `Selector` — все `data class`. Изменения через `copy()` или возвращают новый `Result<T>`.

**Правило**: pure-domain работа идёт через `data class` + `copy()`. Никаких setter-методов на Task/Project. Если нужен mutation — `Task.copy(priority = X)` возвращает новый, и явно передаётся через `TaskRepository.update`. _(this ADR)_

#### R4. Pipeline composition: каждый шаг pure, failure → empty

`org-agenda-list` — это pure pipeline: `files → parse → extract → filter → group → sort → render`. Failure на любом шаге → empty agenda, не exception. Это контрастирует с strict-mode подходом, где каждый failure кидает `Result.Left`.

Kotlin-проекция: `AgendaEvaluator.evaluate` уже чистый pipeline (`feature/agenda/domain/logic/AgendaEvaluator.kt:36`). Empty `List<Task>` → empty sections, не throw.

**Правило**: новый pure-domain pipeline (filter → group → sort → render) возвращает пустую коллекцию на no-match, а не `Result.Left(Empty)`. `Result.Left` зарезервирован для validation/business-rule failures (см. `TaskDomain.assertNoNesting` как reference). _(this ADR)_

#### R5. Homoiconic sealed hierarchy: data ↔ predicate ↔ JSON

`org-element` AST — это одновременно:
- Typed structure (52 node types как `sealed`),
- Predicate input (`org-element-map` принимает type для фильтрации),
- JSON-serializable (`org-element-interpret-data` round-trip).

Один тип — три роли. Никаких параллельных DTO.

Kotlin-проекция: `sealed interface Selector` уже отвечает этому (`@Serializable`, предикат, JSON-через-`SelectorSerializer`). Расширение:
- `TaskFilter` (`Task.kt:32`) должен стать `@Serializable` для consistency — у него сейчас нет `@Serializable`, что разрывает контракт.
- `AgendaBadge` (из `AgendaEvaluator.computeBadge`) — должен стать `@Serializable` если пойдёт в saved views.

**Правило**: новый sealed hierarchy для DSL-предикатов (filter, selector, transformer, predicate) обязан одновременно быть `@Serializable` и pure predicate. Никаких параллельных DTO, никаких отдельных `Foo.toDto()` методов. _(this ADR — reinforces ADR 17a P0.3)_

### Расширенный roadmap (дополнение к ADR 17a)

| Sprint | Что добавляется | Файл | Этот ADR |
|---|---|---|---|
| A | `Cascade.kt` + `TreeVisitor.kt` | новые | covered by ADR 17a |
| **A+** | `Ancestors.kt` (parent-chain walk) | новый | **this ADR** |
| B | `Computed.kt` | новый | covered by ADR 17a |
| C | `SelectorTransformer` | расширение DSL | covered by ADR 17a |
| **C+** | `Bulk.kt` (`mapEntries` helper) | новый | **this ADR** |
| **C+** | `Attr.kt` (extension для default values) | новый | **this ADR** |
| D | Round-trip tests для `Selector`, `AgendaDefinition`, `AgendaBadge` | `commonTest` | covered by ADR 17a |

`A+` — крошечный pure-helper: `T.ancestors(all, parentOf): Sequence<T>`. Это абстракция над `cascadeUp` для случая, когда нужна вся цепочка, а не одно значение.

`Bulk.kt` — `fun List<Task>.mapEntries(today, transform): List<RenderedEntry>` — generic projection из Org-mode `org-map-entries`. Не требует Room-миграции, чистая утилита.

`Attr.kt` — `fun <T, V> T.attr(extractor: (T) -> V?, default: V): V` — нулевой boilerplate для default values. Универсально применимо, не специфично для tasks.

### Конкретные файлы для sprint A+ / C+

| Файл | Сигнатура (без реализации) |
|---|---|
| `core/tree/Ancestors.kt` | `fun <T, K> T.ancestors(all: List<T>, parentOf: (T) -> K?): Sequence<T>` |
| `feature/tasks/domain/logic/Bulk.kt` | `inline fun <E> List<Task>.mapEntries(today: LocalDate, transform: (Task, LocalDate) -> E): List<E>` |
| `core/pure-formatters/Attr.kt` | `inline fun <T, V> T.attr(extractor: (T) -> V?, default: V): V = extractor(this) ?: default` |

Каждый файл — `internal` scope, single responsibility, покрывается `commonTest` (per ADR 17a: empty list, single element, deep nesting, cycle detection).

### Чего НЕ переносим

- **Plist-стиль `Map<String, Any>`** — Kotlin `data class` строже и читаемее.
- **Динамическая диспетчеризация по типу** (Elisp `cl-case` / `typecase`) — Kotlin `when` exhaustive, лучше.
- **Hash-table кэширование с text-property invalidation** — в Kotlin это не идиоматично, заменяется `rememberSaveable` / KDataStore.
- **`symbol` vs `keyword` vs `string`** distinctions — в Kotlin нет, и не нужно.
- **Dynamic scoping** (`defvar`, `setq`) — Kotlin type-safety убирает это полностью.

## Rationale

- **Минимальный риск.** Дополнение к существующему roadmap, не новый рискованный модуль. `Ancestors.kt`, `Bulk.kt`, `Attr.kt` — pure-helpers на 5-20 строк каждый.
- **Сильнее, чем Elisp.** Compile-time `when` exhaustive > runtime symbol-check `org-element-map`. `data class.copy()` > cons-cell mutation. Kotlin `inline fun` > Elisp dynamic dispatch.
- **Pure-инвариант уже есть.** `AgendaEvaluator.evaluate` и `TaskDomain.matchesFilter` — pure; `cascadeUp`, `mapEntries`, `attr` — следуют тому же правилу.
- **Закрывает дрейф.** Без явных правил R1-R5 будущие рефакторинги могут случайно ослабить pure-function контракт (например, добавить stored `effectivePriority` для "производительности").
- **Парность ADR-ов.** ADR 17a фиксирует **что** строить (Cascade, Visitor, Computed, Transformer); этот ADR фиксирует **как** строить (R1-R5 правила).

## Consequences

- **Always** route derived values (`effectivePriority`, `effectiveColor`, `effectiveTags`) through `core/tree/Cascade.kt` `cascadeUp` — never as stored fields on entities. _(from `2026-09-17-orgmode-functional-patterns`)_
- **Always** use `data class.copy()` for Task/Project/Tag/AgendaDefinition mutations in pure-domain code — never add setters. Mutations go through `TaskRepository.update(...)`. _(from `2026-09-17-orgmode-functional-patterns`)_
- **Always** return empty collection (not `Result.Left(Empty)`) for no-match cases in pure-domain pipelines like `AgendaEvaluator`. `Result.Left` is reserved for validation/business-rule failures only. _(from `2026-09-17-orgmode-functional-patterns`)_
- **Always** make new sealed hierarchies for DSL predicates (filter, selector, transformer, predicate) simultaneously `@Serializable` AND pure predicate — no parallel DTOs. _(from `2026-09-17-orgmode-functional-patterns`)_
- **Never** introduce `Map<String, Any>` plist-style containers in Kotlin domain code — use `data class` instead. _(from `2026-09-17-orgmode-functional-patterns`)_
- **Never** store memoization caches inside pure-domain functions — memoization is an outer wrapper (e.g. `rememberSaveable`, KDataStore, VM-side `StateFlow`). _(from `2026-09-17-orgmode-functional-patterns`)_
- `core/tree/Ancestors.kt`, `feature/tasks/domain/logic/Bulk.kt`, `core/pure-formatters/Attr.kt` are optional add-ons — they may be added in any sprint A+/C+ order or skipped entirely if not yet needed. _(from `2026-09-17-orgmode-functional-patterns`)_
- This ADR layers on top of `2026-09-17-orgmode-architectural-lessons.md` and supersedes nothing. Both ADRs are read together at sprint planning time. _(from `2026-09-17-orgmode-functional-patterns`)_

## Links

- ADR: `2026-09-17-orgmode-architectural-lessons.md` — парный ADR (infrastructure modules)
- ADR: `2026-09-16-agenda-engine.md` — `AgendaEvaluator.evaluate` как reference pure pipeline
- ADR: `2026-09-15-viewmodel-state-ownership.md` — pure-domain ↔ side-effect граница
- Skill: `singularity-todo-decisions-workflow` — формат ADR
- Skill: `singularity-todo-pure-formatters` — соседний pure-инфра слой
- Skill: `singularity-todo-quality-tools` — kover gate для новых pure-модулей
- Code: `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/model/Task.kt` — примеры `isCompleted`/`isTrashed` как parsed/derived разделения
- Code: `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/TaskDomain.kt` — `matchesFilter`, `assertNoNesting` как pure pipeline
- Code: `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/logic/AgendaEvaluator.kt` — pure multi-stage pipeline
- Code: `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/model/Selector.kt` — homoiconic `@Serializable` sealed predicate
- Code: `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/model/AgendaDefinition.kt` — DSL с `@AgendaDslMarker`
- Code: `shared/src/commonMain/kotlin/com/singularity/todo/core/serialization/StableJson.kt` — round-trip JSON для DSL sealed hierarchies
