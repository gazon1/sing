---
status: accepted
date: 2026-09-25
deciders: Singularity Developer
---

# Post-MR-7 Audit (MR-6a + MR-7 completed)

## Context

После завершения MR-6a (CalendarVM + ChatVM миграция) и MR-7 (StateFlowExt deletion) проведён аудит оставшихся VM на предмет критических багов и технического долга.

**Состояние:** BUILD SUCCESSFUL, jvmTest passed, detekt clean.

---

## Critical Bugs Found and Fixed

### SearchViewModel.onTogglePin — crash on pin (fixed in MR-6a)

`throw IllegalStateException` внутри `fireAndForget` → краш приложения. Пофикшено добавлением `TaskRepository` и вызовом `taskRepo.togglePinned()`.

---

## Remaining Issues

### 1. BackupViewModel — 2× MutableSharedFlow instead of EventBus

**Файл:** `feature/backup/BackupViewModel.kt:57,60`

```kotlin
private val _events = MutableSharedFlow<BackupUiEvent>(extraBufferCapacity = 4)
private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 4)
```

**Проблема:** Два разных SharedFlow для разных типов событий. При миграции на MviViewModel — объединить в единый EventBus.

**Severity:** Medium. Работает, но не консистентно с MVI framework.

**План:** MR-6b (BackupVM миграция).

---

### 2. SearchViewModel — MutableSharedFlow for events

**Файл:** `feature/search/SearchViewModel.kt:130`

```kotlin
private val _events = MutableSharedFlow<SearchUiEvent>(extraBufferCapacity = 4)
```

**Проблема:** Hand-rolled SharedFlow вместо EventBus. Также `_state` обновляется напрямую через `MutableStateFlow`, не через MviViewModel framework.

**Severity:** Medium.

**План:** MR-6c (SearchVM миграция).

---

### 3. TaskDetailViewModel._latestTask TOCTOU

**Файл:** `feature/tasks/presentation/viewmodel/TaskDetail.kt:211`

```kotlin
fun onIntent(intent: TaskDetailIntent.Domain) {
    val current = _latestTask.value ?: return  // TOCTOU
    when (intent) {
        is TaskDetailIntent.Domain.ToggleComplete -> {
            // использует `current`, но _latestTask.value мог измениться
        }
    }
}
```

**Проблема:** `val current = _latestTask.value` снимается в момент T1, но `current` используется после. Если `_latestTask.value` обновится между T1 и использованием — intent сработает на устаревшую задачу.

**Severity:** Low. Редко проявляется в practice.

**План:** MR-6d (TaskDetailVM миграция + TOCTOU fix).

---

### 4. fireAndForget vs vmScope.launch in SettingsViewModel

**Файл:** `feature/settings/SettingsViewModel.kt`

17 вызовов `scope.fireAndForget(...)`. Все с `onError` — безопасно. No crash risk.

**Проблема:** Boilerplate. При миграции SettingsVM на MviViewModel — заменить на `vmScope.launch { ... }.onFailure { emit(...) }` через EventBus.

**Severity:** Low (working code).

**План:** MR-6b.

---

### 5. AgendaVM / SavedAgendaListVM redundant updateState { it }

```kotlin
.collect { updateState { it } }  // redundant: эквивалентно setState(it)
```

**Severity:** Very Low. Code style issue, не баг.

**План:** Low-priority cleanup в любом MR.

---

### 6. AgendaViewModel, SavedAgendaViewModel, ArchiveViewModel, AiUsageViewModel — updateState without import

Все эти VM наследуют MviViewModel и используют `updateState` как protected метод — никаких imports не нужно. Это корректно и не требует действий.

---

## Summary Table

| # | Проблема | VM | Severity | Действие |
|---|---|---|---|---|
| 1 | 2× MutableSharedFlow | BackupVM | Medium | MR-6b |
| 2 | MutableSharedFlow + hand-rolled state | SearchVM | Medium | MR-6c |
| 3 | TOCTOU _latestTask | TaskDetailVM | Low | MR-6d |
| 4 | fireAndForget boilerplate | SettingsVM | Low | MR-6b |
| 5 | redundant updateState { it } | AgendaVM, etc. | Very Low | optional |
| — | StateFlowExt deleted | — | Done | MR-7 ✓ |

---

## No Issues Found (verified clean)

- ❌ `throw` statements в VM: только в domain/use cases (легально)
- ❌ `_errorMessage` declaration order: пофикшено в MR-6a
- ❌ MutableSharedFlow crashes: пофикшено (SearchVM), остальные с onError
- ❌ StateFlowExt usage: удалён в MR-7
- ❌ Duplicate `ChatUiEvent.kt`: удалён в MR-6a
- ❌ CalendarViewModel copy(): пофикшено (использует private CalendarState.copy())

---

## Actions

| MR | VMs | Tasks |
|---|---|---|
| MR-6b | SettingsVM, BackupVM, CalendarSyncVM, SyncVM | EventBus unification |
| MR-6c | SearchVM, ProjectsVM | MutableSharedFlow → EventBus |
| MR-6d | TaskDetailVM | TOCTOU fix + DraftMviViewModel |

---

## Consequences

- Критических багов после MR-7 нет
- Technical debt: 4 VM с MutableSharedFlow, 1 с TOCTOU
- Все VMs на MviViewModel/DraftMviViewModel кроме: BackupVM, SearchVM, TaskDetailVM, SettingsVM, ProjectsVM, ProjectDetailVM, CalendarSyncVM, SyncVM
