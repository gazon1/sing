---
date: 2026-09-14
tags: [architecture, navigation, koin, viewmodel, bug]
---

# Nav3 ViewModelStore decorator fix

## Context

Эмпирический аудит (см. подробный отчёт в финальном плане миграции
tasks-фичи) показал, что `koinViewModel { parametersOf(taskId) }` внутри
`entry<AppDestination.X>` сейчас **не создаёт отдельный VM на каждое
значение параметра**. Причина — отсутствие
`rememberViewModelStoreNavEntryDecorator` в `Nav3State.toDecoratedEntries`.

Цепочка резолва `LocalViewModelStoreOwner.current` внутри entry:

1. `Nav3State.toDecoratedEntries` (`Nav3State.kt:48-58`) устанавливает
   только `rememberSaveableStateHolderNavEntryDecorator<NavKey>()` —
   per-entry `ViewModelStoreOwner` НЕ провайдится.
2. `LocalViewModelStoreOwner.current` поднимается вверх по композиции
   до `ComponentActivity` (`MainActivity.kt:14-23`).
3. Все `entry<...>` лямбды делят **одну** `ViewModelStore` в пределах
   Activity.
4. `koinViewModel(ViewModel.kt:49)` берёт
   `viewModelStoreOwner.viewModelStore`.
5. `resolveViewModel(GetViewModel.kt:60)` вызывает
   `provider[vmClass]` — store keyed **только по class FQN**.
6. `parametersOf(...)` (`KoinViewModelFactory.kt:46-49`) используется
   только при первом создании VM; при повторном запросе возвращается
   кэшированный instance с **прошлым** параметром.

## Decision

Подключаем `rememberViewModelStoreNavEntryDecorator` на обоих уровнях
nav3-стека:

1. **Outer NavDisplay** (в `Nav3State.toDecoratedEntries`,
   `Nav3State.kt:48-58`) — добавляем
   `rememberViewModelStoreNavEntryDecorator<NavKey>()` в список
   декораторов.
2. **Inner TasksNavGraph** (новый `TasksNavGraph.kt`) — добавляем
   в `entryDecorators` `NavDisplay`.

Это вводит per-entry `ViewModelStoreOwner` через `LocalViewModelStoreOwner`,
и `parametersOf(taskId)` начинает работать как identity key для VM scope.

## Rationale

- Это **официально рекомендованный** путь из nav3-recipes
  `passingarguments/viewmodels/koin`: «Make sure you use
  `rememberViewModelStoreNavEntryDecorator()` if you want a new
  ViewModel for each new navigation key instance.»
- Альтернативы (manual `CompositionLocalProvider(LocalViewModelStoreOwner
  provides entryViewModelStoreOwner)`) — нестандартны и хрупки.
- Подключение влияет на все фичи (notes/projects/auth/settings), но
  не ломает существующее поведение: VM без `parametersOf` (`koinViewModel()`
  без аргументов) получают per-entry scope вместо per-Activity —
  поведение более корректное (VM очищается при удалении entry из стека).

## Consequences

### Положительные

- Чинится латентный bug для всех `koinViewModel { parametersOf(...) }`
  callsites в проекте:
  - `TaskDetailViewModel(taskId)` — Task A → back → Task B больше
    не показывает state от A.
  - `TaskCreateViewModel(initialDueDate)` — два последовательных
    создания с разными датами не переиспользуют VM.
  - `ProjectDetailViewModel(projectId)` — Project X → back → Project Y
    не показывает state от X.
  - Все остальные параметризованные VM (~20 callsites).
- Lifecycle VM становится привязан к lifetime entry — VM очищается
  когда entry удаляется из стека (через `onCleared()`).

### Отрицательные

- Diff больше, чем чисто миграция tasks — затрагивает общий `Nav3State`.
- Если какой-то VM был неявно расчитан на per-Activity scope
  (например, для cross-screen кэша), он начнёт очищаться чаще.
  Аудит не выявил таких случаев, но стоит прогнать ручную проверку
  на заметных экранах после PR.
- Существующие unit-тесты для VM не затрагиваются (тестируют VM
  изолированно, без nav3 scope).
- Instrumented/integration тесты (`CreateTaskFlowInstrumentedTest`)
  могут требовать обновления, если полагались на activity-scoped VM.

### Future work

- Рассмотреть переход на `LocalResultEventBus` + `ResultEffect<T>` для
  navigation-result events (Saved → exit, UndoDelete) — официальный
  nav3-recipes/results-event pattern. Не блокирует текущий PR.

## Links

- `2026-09-11-nav3-kmp-migration.md` — базовая nav3 архитектура
- `2026-09-14-tasks-feature-nested-nav3.md` — миграция tasks (motivation)
- nav3-recipes `passingarguments/viewmodels/koin`
