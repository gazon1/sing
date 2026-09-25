---
status: accepted
date: 2026-09-25
deciders: Singularity Developer
---

# MR-6a Audit Findings

## Context

После миграции CalendarViewModel и ChatViewModel на MviViewModel (MR-6a), проведён аудит оставшихся VM на предмет критических багов, блокеров и технического долга.

## Critical Bug Fixed

### SearchViewModel.onTogglePin — crash on pin action

**Файл:** `feature/search/SearchViewModel.kt:344`

**Проблема:** `onTogglePin` выбрасывала `IllegalStateException` внутри `fireAndForget`, что приводило к крашу приложения при попытке закрепить задачу из экрана поиска.

```kotlin
// BEFORE (crash)
private fun onTogglePin(taskId: TaskId) {
    scope.fireAndForget(...) {
        throw IllegalStateException("Toggle pin should go through TaskRepository directly")
    }
}
```

**Фикс:** Добавлен `TaskRepository` в зависимости `SearchViewModel`, `onTogglePin` теперь вызывает `taskRepo.togglePinned(taskId)`.

**Commit:** `4232858c`

---

## Non-Critical Issues (for future MRs)

### 1. BackupViewModel, SearchViewModel — hand-rolled MVI pattern

**Статус:** На очереди для MR-6b (BackupVM) и MR-6c (SearchVM).

| VM | Events | State | Scope | Plan |
|---|---|---|---|---|
| `BackupViewModel` | `MutableSharedFlow` (2 шт.) | `MutableStateFlow` | конструктор | MR-6b |
| `SearchViewModel` | `MutableSharedFlow` | `MutableStateFlow` | конструктор | MR-6c |

BackupViewModel использует 2 отдельных `MutableSharedFlow` (`_events` + `_snackbar`). При миграции на MviViewModel — объединить в один EventBus.

### 2. MutableSharedFlow в легальных местах

**Файлы:**
- `core/ui/EventBus.kt:42` — internal SharedFlow backend для EventBus
- `TaskDetail.kt:56-57` — `titleEdits` и `descriptionEdits` как input channels для debounce-паттерна (не events в смысле MVI)

Эти использования — легальные (внутренние input channels, не публичные event API). Не требуют миграции.

### 3. TaskDetailViewModel._latestTask TOCTOU

**Файл:** `feature/tasks/presentation/viewmodel/TaskDetail.kt:211`

```kotlin
fun onIntent(intent: TaskDetailIntent.Domain) {
    val current = _latestTask.value ?: return  // TOCTOU: value can change between check and use
    when (intent) {
        is TaskDetailIntent.Domain.ToggleComplete -> { ... current ... }
    }
}
```

**Проблема:** `val current = _latestTask.value` снимается в момент T1, но `current` используется позже в intent handler. Если `_latestTask.value` обновится между T1 и использованием `current`, intent сработает на устаревшую задачу.

**Severity:** Low. В типичном use-case user click → intent → handler быстро выполняется. На момент MR-6d (TaskDetail) — исправить на `updateState { ... }` редуктор вместо чтения поля напрямую.

### 4. StateFlowExt.kt — deprecated but still used

**Файл:** `core/ui/state/StateFlowExt.kt`

12+ VM используют `updateState` (StateFlowExt) вместо `MviViewModel.updateState`. Это работает потому что StateFlowExt — extension на `MutableStateFlow`, а не на VM.

**План:** Удалить после миграции всех VM в MR-6b/6c/6d (MR-7).

### 5. 4 VM на MviViewModel с override val vmScope

После добавления `protected open val vmScope` в MviViewModel, 6 subclassов объявили `override val vmScope`:
- `DraftMviViewModel`, `AuthViewModel`, `CalendarSyncViewModel`, `NotePreview`, `ProfileSwitcherViewModel`, `SyncViewModel`

Это boilerplate — можно удалить `override val vmScope` из subclassов, так как `protected open` даёт доступ напрямую. Однако удаление может сломать код который полагается на то что `vmScope` — это именно `scope` переданный в конструктор (а не inherited).

**Решение:** Оставить как есть. При финальном рефакторинге MviViewModel API (MR-8) — пересмотреть.

### 6. AgendaViewModel и SavedAgendaViewModel — updateState { it }

```kotlin
.collect { updateState { it } }
```

Это redundancy — `updateState { it }` эквивалентен `setState(it)`. Работает корректно, но избыточно. Low-priority cleanup.

---

## Actions

| # | Что | Кто | MR |
|---|---|---|---|
| 1 | BackupViewModel → MviViewModel | Agent | MR-6b |
| 2 | SearchViewModel → MviViewModel | Agent | MR-6c |
| 3 | TaskDetailVM TOCTOU fix | Agent | MR-6d |
| 4 | Delete StateFlowExt.kt | Agent | MR-7 |
| 5 | Remove redundant override val vmScope | Agent | MR-8 |

---

## Consequences

- SearchViewModel больше не крашнется при закреплении задачи из поиска
- BackupViewModel и SearchViewModel остаются на legacy MVI pattern до своих MR
- StateFlowExt deprecated, но не удаляется пока все VM не мигрированы
