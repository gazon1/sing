---
title: Test Standards — Enforcement, Gap Filling, and Architecture Cleanup
status: accepted
date: 2026-09-23
authors: ZCode Agent
deciders: Singularity Developer
---

> **Superseded in part (2026-09-28):** §5 below states that
> `FakeProfileAwareCurrentUser` "запускает collectors на `Dispatchers.Default`". That
> stopped being true on 2026-09-25, when `560f3bf8` moved the default to
> `Dispatchers.Unconfined` — an eager dispatcher that drains inline. A probe test in
> roadmap MR-1 confirmed `advanceUntilIdle()` drives the whole slot suite; the
> `CalendarViewModelTest` workaround comments are no longer needed for this reason.
> See [2026-09-28-mr1-test-virtualization-retro.md](2026-09-28-mr1-test-virtualization-retro.md).

## Context

Тест-аудит `singularity_cllone_kmp` выявил системные проблемы в тестах и production-коде:

### 1. `stateIn(WhileSubscribed(5000))` анти-паттерн

13 ViewModel использовали `stateIn(scope, WhileSubscribed(5000), initialValue)`. Это:
- Держит upstream active 5 секунд после отписки → `UncompletedCoroutinesError` в тестах
- Работает только при активном subscriber — без `vm.state.launchIn(scope)` в тестах state never transitions
- Сокрытие ошибок: upstream умирает silent, UI показывает `Loading` вечно

### 2. Side-effects внутри `combine`/`flatMapLatest`

`TaskDetailViewModel` и `ProjectDetailViewModel` писали `_latestTask.value = task` **внутри** `flatMapLatest` / `combine`. Это:
- Нарушает чистоту reactive chain
- TOCTOU race: между чтением `_latestTask` и записью может прилететь remote edit

### 3. `AccountSettingsViewModel` — бессмысленная прослойка

Один `Flow` наружу, никакой логики. Заменим на `koinInject<ProfileRepository>()` в Composable.

### 4. Нетестированные critical paths

- `RoomUsageRecorder` — ни одного теста
- 42× `delay(N)` в тестах (реальное время, не virtual time)
- 10× `runBlocking` в production
- 7× `viewModelScope` в production (вместо инъектируемого scope)

---

## Decisions

### D1: Все 9 ViewModel мигрированы на `MutableStateFlow` + `scope.launch { }.collect {}`

Миграция проведена для:
- `TagsViewModel`
- `SavedAgendaListViewModel`
- `AgendaViewModel`
- `AiUsageViewModel`
- `ArchiveViewModel`
- `TaskDetailViewModel` (side-effect вынесен в отдельный collector)
- `ProjectDetailViewModel` (4× stateIn + combine side-effect вынесен)
- `CalendarViewModel`
- `TaskCreateViewModel`
- `ProjectsViewModel`
- `StatisticsViewModel`
- `ProfileSwitcherViewModel`

Canonical pattern:
```kotlin
// ANTIPATTERN — не использовать
val state = someFlow.stateIn(scope, WhileSubscribed(5000), Initial)

// PATTERN — канонический
private val _state = MutableStateFlow<UiState>(UiState.Loading)
val state: StateFlow<UiState> = _state.asStateFlow()

init {
    addCloseable(scope)
    scope.launch {
        someFlow.collect { _state.value = it }
    }
}
```

### D2: `AccountSettingsViewModel` удалён

Заменён на `koinInject<ProfileRepository>()` в `AccountSettingsScreen`. Удалены:
- `AccountSettingsViewModel.kt`
- DI registration
- `AccountSettingsViewModelTest.kt`

### D3: `FileSystemContractTest` добавлен

Contract test для `FileSystem` порта (path normalization, trailing slash, atomic replace). Также исправлен bug в `MapFileSystem.listDir` — `trimEnd('/')` на входном `dir`.

### D4: `BackupCodecContractTest` добавлен

Contract test для `BackupCodec` порта (round-trip по версиям).

### D5: `LlmUsageRecorderTest` добавлен

5 тестов для `RoomUsageRecorder`:
- Успешное событие
- Failure-событие с error message
- Profile isolation
- Aggregation по tool
- prune retention window

---

## Pre-existing Test Failures (9)

Эти failures **не вызваны** изменениями в этой ADR. Проверено: identical failures на `origin/main`.

| Test | Причина |
|---|---|
| `AnalyticsTest.logEventOncePerDay` | Pre-existing |
| `AnalyticsTest.logEventOncePerDay` (2nd) | Pre-existing |
| `BackupOptionsTest.exportOptions` | Pre-existing |
| `DiGraphTest.UI ports registered` | Pre-existing |
| `DiGraphTest.core domain modules` | Pre-existing |
| `JvmAiDiGraphTest.full AI module` | Pre-existing |
| `FileLogWriterTest.logFiles` | Pre-existing |
| `SavedAgendaViewModelTest.sectionsReorderedSetsIsDirty` | Pre-existing |

---

## Остаточные проблемы (требуют отдельной ADR)

### 1. `stateIn` в не-VM коде (низкий приоритет)

- `ProfileRepositoryImpl.kt:45` — `ProfileId.default.stateIn` (startup, не critical)
- `ProfileAwareCurrentUser.kt:45` — то же

Эти не ViewModel, policy для них не определена.

### 2. `delay(N)` в тестах (42 occurrences)

42 теста используют `delay(N)` с реальным временем. Надо заменить на `advanceUntilIdle()` + debounce mocking.

### 3. `runBlocking` в production (10 occurrences)

10× в `androidMain`/`jvmMain`. Нужно:
- ban через `NoRunBlockingRule` (уже написан, но в warning mode)
- перенести в `scope.launch { }`

### 4. `viewModelScope` в production (7 occurrences)

7× `viewModelScope.launch` в production. Нужно:
- ban через `NoViewModelScopeInProductionRule` (уже написан, но в warning mode)
- заменить на инъектированный scope

### 5. Fake-Repository isolation issues

- `FakeProfileAwareCurrentUser` запускает collectors на `Dispatchers.Default` — `advanceUntilIdle` не двигает виртуальное время для этой работы
- `CalendarViewModelTest` имеет workaround-комментарии про это

### 6. `SyncEndToEndTest`, `ReminderDSTTest`, `MultiProfileIsolationTest`

Не написаны — нужен отдельный exploration.

---

## Consequences

### Positive
- Все 13 VM теперь используют канонический паттерн `MutableStateFlow + scope.launch { }.collect {}`
- Side-effects вынесены из reactive chains
- `AccountSettingsViewModel` убран — меньше boilerplate
- Новая test coverage для `LlmUsageRecorder`

### Negative
- Миграция touching 13 файлов — высокий риск merge conflict при parallel development
- Detekt rules (`NoViewModelScopeInProductionRule`, `NoRunBlockingRule`) работают в warning mode — нужно перевести в error после baseline

---

## Links

- Skill: `singularity-todo-test-helpers`
- Skill: `singularity-todo-testable-vm`
- Skill: `singularity-todo-vm-migration-playbook`
- Skill: `singularity-todo-coroutine-scopes`
- ADR: `2026-09-18-testing-best-practices`
