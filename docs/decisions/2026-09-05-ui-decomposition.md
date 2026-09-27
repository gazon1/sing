---
title: UI Decomposition — reusable widgets, per-feature events, use-case extraction
date: 2026-09-05
status: accepted
---
# UI Decomposition — reusable widgets, per-feature events, use-case extraction

## Context

UI-слой имел три системные проблемы:

1. **Копипаста boilerplate** — `when (state) { Loading/Empty/Error/Ready }` и `Scaffold + TopAppBar + ArrowBack` дублировались на 7+ экранах
2. **Глобальная утечка типов** — `UiEvent.ShowDialog / ShowError / NavigateBack` конфликтовали при масштабировании фич
3. **Side effects в VM** — `viewModelScope.launch { repo.toggleComplete() }` без use case, `delay(500ms)` в `NotesViewModel.scheduleAutosave`

## Decision

### A. Новые виджеты (`core/ui/components/`)

| Файл | Назначение | Ключевой паттерн |
|---|---|---|
| `NotificationHost.kt` | Generic overlay для one-shot событий | `<T>` generic + `mapper: (T) → Notification` |
| `BackTopAppBar.kt` | Scaffold + TopAppBar + back в одном | `content: @Composable (PaddingValues) → Unit` slot |
| `StatefulContent.kt` | Заменяет `when (state) { Loading/Empty/Error/Ready }` | Sealed `ContentState<out T>` |
| `IconPickerRow.kt` | Tappable row с composable icon slot | `icon: @Composable () -> Unit` |
| `ChecklistItemRow.kt` | Stateless checklist item | primitives only, no domain type |

**Все виджеты pure** — никаких side effects, только Compose.

### B. Per-feature events

Каждая фича получила свой sealed interface:

- `TasksUiEvent` — `AiResult`, `Error`, `NavigateBack`
- `NotesUiEvent` — `AiResult`, `SaveFailed`, `NavigateBack`
- `ProjectsUiEvent` — `ProjectReviewResult`, `Error`
- `ChatUiEvent` — `Error`
- `ArchiveUiEvent` — `Archived`, `Error`

`core/ui/components/UiEvent.kt` стал **empty marker interface**.

`NotificationHost` больше не ограничен `T : UiEvent` — generic без bound.

### C. Use cases для side effects

| Use case | Бизнес-логика | Файл |
|---|---|---|
| `DeleteTaskUseCase` | soft-delete wrapper | `feature/tasks/usecase/` |
| `ToggleTaskUseCase` | toggle complete wrapper | `feature/tasks/usecase/` |
| `TogglePinUseCase` | toggle pin wrapper | `feature/tasks/usecase/` |
| `BulkCompleteUseCase` | batch complete (atomic via `getOrThrow`) | `feature/tasks/usecase/` |
| `BulkDeleteUseCase` | batch delete (atomic) | `feature/tasks/usecase/` |
| `DeleteProjectUseCase` | **Реальная логика**: guard — нельзя удалить проект с задачами | `feature/projects/usecase/` |
| `DeleteTagUseCase` | tag delete wrapper | `feature/tags/usecase/` |
| `ChecklistUseCase` | addItem/toggleItem/deleteItem с timestamp | `feature/checklist/` |

### D. AutosaveScheduler port

```kotlin
interface AutosaveScheduler {
    suspend fun awaitTick()
}
class DelayAutosaveScheduler(delayMs: Long = 500L) : AutosaveScheduler
```

`NotesViewModel` принимает `AutosaveScheduler` через конструктор. Тесты используют `DelayAutosaveScheduler(Long.MAX_VALUE)` (никогда не тикает) или кастомный stub.

### E. scopeOverride для тестируемости

VMs с `scopeOverride`:
- ✅ `TasksViewModel`
- ✅ `NotesViewModel`
- ✅ `ProjectsViewModel` (добавлен в этой сессии)
- ❌ `TagsViewModel` — нет `scopeOverride`, использует `viewModelScope` напрямую
- ❌ `TaskDetailViewModel` — нет `scopeOverride`, 4 `viewModelScope.launch`

## Consequences

- `NotificationHost` заменил ~64 строки ручного glue кода на 8 экранах
- Per-feature events устранили конфликты имён (до: `ShowDialog` everywhere; после: `TasksUiEvent.AiResult`, `NotesUiEvent.SaveFailed`)
- Autosave вынесен из `delay()` в VM в отдельный port — теперь тестируем без `advanceTimeBy`
- 8 экранов мигрированы: Tasks, Notes, TaskDetail, TaskEditor, Projects, ProjectEditor, Chat, Archive
- `scopeOverride` добавлен в `ProjectsViewModel`

## Technical debt (known issues)

### FakeRepositories seed() неконсистентны

| Repository | seed() поведение |
|---|---|
| `FakeTaskRepository`, `FakeChecklistRepository` | **REPLACES** всю мапу |
| `FakeProjectsRepository`, `FakeTagsRepository`, `FakeNotesRepository` | **ADDS** в мапу |

Тесты падали из-за этого бага. Также: `FakeNotesRepository.searchNotes` содержит typo `it.userId.value == it.userId.value` вместо `it.userId == userId`.

**FIX NEEDED**: унифицировать `seed()` на ADD-поведение, добавить `add()` / `clear()`, починить typo.

### FakeAutosaveScheduler.trigger() — пустая заглушка

```kotlin
fun trigger() {
    // If already waiting, fire immediately; otherwise do nothing
}
```

`CompletableDeferred` никогда не completion, `trigger()` не делает ничего. Autosave integration tests не могут надёжно проверить "тикнул/не тикнул".

**FIX NEEDED**: реализовать `trigger()` через `deferred.complete(Unit)` + fresh deferred.

### Pass-through use cases

`DeleteTaskUseCase`, `ToggleTaskUseCase`, `TogglePinUseCase` — чистые обёртки над `repo.method()`. Создают boilerplate без добавленной стоимости. Стоит либо удалить и инжектить `TaskRepository` напрямую, либо объединить в `TaskMutationsUseCase`.

**FIX NEEDED**: консолидация или удаление.

**Resolved by [2026-09-18-no-pass-through-usecases.md](2026-09-18-no-pass-through-usecases.md)** — those three files were already removed in R10 (ARCHITECTURE.md:418). `ChecklistUseCase` (the only remaining violator) was trimmed in the same commit. `PassThroughUseCase` detekt rule prevents regression.

### BulkCompleteUseCase — неатомарность

Если первый `toggleComplete` succeeds, а второй fails — состояние уже изменено (partial rollback невозможен).

**FIX NEEDED**: валидировать ВСЕ id перед любой мутацией.

### ContentState.toContentState() — непроверяемо

`toContentState()` — extension functions на VM-типах. Не тестируются изолированно без создания full VM.

**FIX NEEDED**: вынести в `ContentStateMapper` object с pure functions.

### TagsViewModel и TaskDetailViewModel — без scopeOverride

`TagsViewModel` и `TaskDetailViewModel` используют `viewModelScope` напрямую, без `scopeOverride`. Это делает невозможным надёжное тестирование их корутинной логики через `advanceUntilIdle`.

**FIX NEEDED**: добавить `scopeOverride: CoroutineScope? = null` и заменить `viewModelScope` на `scope`.

## Links

- `docs/decisions/2026-09-05-ui-event-per-feature.md`
- `docs/decisions/2026-09-05-robolectric-widget-tests.md`
