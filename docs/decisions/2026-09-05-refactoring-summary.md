---
title: ADR: Рефакторинг — унификация, scopeOverride, TaskMutationsUseCase, ContentStateMapper
date: 2026-09-05
status: accepted
summary: Рефакторинг 7 пунктов: FakeRepositories seed/unification, scopeOverride в TagsVM+TaskDetailVM, TaskMutationsUseCase вместо 5 pass-through UC, атомарная валидация bulk-операций, ContentStateMapper
---

# ADR: Рефакторинг — унификация, scopeOverride, TaskMutationsUseCase, ContentStateMapper

## Context

После сессии UI-декомпозиции (ADR `2026-09-05-ui-decomposition`) остались 7 технических долгов,
выявленных при анализе кодовой базы. Каждый пункт — самостоятельная проблема,
но все они касаются тестируемости, консистентности fake-репозиториев и устранения
pass-through use cases.

## Decision
### 1. FakeRepositories: seed() → ADD, add()/clear(), searchNotes fix

**Problem:** `seed()` в `FakeTaskRepository` и `FakeChecklistRepository` заменял (_REPLACE_) всё состояние,
тогда как в остальных репозиториях — добавлял (_ADD_). Тесты, вызывающие `seed(t1); seed(t2)`,
теряли t1.

**Fix:**
- `seed()` теперь использует `this.tasks.value + tasks.associate {...}` (ADD semantics)
- Добавлены `add()` и `clear()` во все Fake-репозитории для управляемого fixture setup
- Исправлен `searchNotes` в `FakeNotesRepository`: убран tautological предикат
  `it.userId.value == it.userId.value`, который всегда возвращал `true`

### 2. FakeAutosaveScheduler.trigger() implementation

**Problem:** `FakeAutosaveScheduler.trigger()` был noop — `CompletableDeferred` никогда не завершался,
тест на autosave зависал.

**Fix:** `trigger()` теперь вызывает `deferred.complete(Unit)` и создаёт свежий `CompletableDeferred()`
для следующего await.

### 3. scopeOverride в TagsViewModel и TaskDetailViewModel

**Problem:** `TagsViewModel` и `TaskDetailViewModel` использовали `viewModelScope` напрямую,
что делало невозможным управление их coroutine execution в тестах
(в отличие от уже исправленных `ProjectsViewModel`, `NotesViewModel`, `TasksViewModel`).

**Fix:** Добавлен параметр `scopeOverride: CoroutineScope? = null` и вычисляемое свойство
`private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope`.
Все `viewModelScope.launch` → `scope.launch`, `stateIn(viewModelScope, ...)` → `stateIn(scope, ...)`.

### 4. TaskMutationsUseCase — консолидация 5 pass-through use cases

**Problem:** `DeleteTaskUseCase`, `ToggleTaskUseCase`, `TogglePinUseCase`, `BulkCompleteUseCase`,
`BulkDeleteUseCase` — все 5 были чистыми pass-through обёртками над `TaskRepository`.
Создавали шум в DI-графе без добавленной стоимости.

**Fix:**
- Создан `TaskMutationsUseCase` — единая точка инъекции для всех mutation-операций
- `TasksViewModel` теперь инжектит один `mutations: TaskMutationsUseCase`
- Из DI удалено 5 factory, добавлена 1
- Удалены 5 файлов из `feature/tasks/usecase/`
- Тесты `TasksViewModelTest` и `TaskLifecycleIntegrationTest` обновлены

### 5. Атомарная валидация bulk-операций

**Problem:** `bulkComplete(ids)` и `bulkDelete(ids)` начинали мутировать состояние
до проверки существования всех ID. Если ID не существовал — мутация тихо пропускалась
вместо того чтобы fail-fast.

**Fix:**
- В `TaskRepository` добавлен метод `suspend fun exists(id: TaskId): Boolean`
- `bulkComplete` и `bulkDelete` в `TaskMutationsUseCase` теперь сначала проверяют
  `ids.forEach { if (!repo.exists(it)) throw IllegalArgumentException(...) }`
- `exists()` реализована в `TaskRepositoryImpl` через `taskDao.watchById(...).first() != null`
- `exists()` добавлена в `FakeTaskRepository` для консистентности тестов

### 6. ContentStateMapper object

**Problem:** `NotesScreen` и `TagsScreen` содержали локальные 5-строчные `when`-выражения
`toContentState()` с идентичной логикой (Loading/Empty/Error/Content). Копипаста.

**Fix:**
- Создан `ContentStateMapper` object с методами `notes(state: NotesUiState)` и `tags(state: TagsUiState)`
- В каждом screen файле локальный extension стал однострочным:
  ```kotlin
  private fun NotesUiState.toContentState() = ContentStateMapper.notes(this)
  private fun TagsUiState.toContentState() = ContentStateMapper.tags(this)
  ```
- Обновлена документация в `StatefulContent.kt`

### 7. Тесты + check.sh

**Changes:**
- `TasksViewModelTest`: обновлён конструктор VM (вместо 5 UC → 1 `mutations`)
- `TaskLifecycleIntegrationTest`: все use case-тесты переписаны на `TaskMutationsUseCase`
- `check.sh`: все 4 этапа проходят (`jvmTest`, `androidHostTest`, `desktopApp:test`, `androidApp:assembleDebug`)

## Consequences

**Positive:**
- Все fake-репозитории теперь имеют консистентное поведение seed()/add()/clear()
- Все ViewModel'ы с `scopeOverride` — консистентны в тестах
- DI-граф упрощён: 5 factory → 1
- Bulk-операции fail-fast при отсутствующих ID

**Neutral:**
- `TaskMutationsUseCase` — новый класс, но он по сущиности — grouping, не новая логика
- `ContentStateMapper` — добавлен object с двумя методами

## Links

- Parent: [ADR 2026-09-05-ui-decomposition](./2026-09-05-ui-decomposition.md)
- Skill: [singularity-todo-di-graph-testing](../.agents/skills/singularity-todo-di-graph-testing/SKILL.md)
