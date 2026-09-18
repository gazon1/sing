---
date: 2026-09-14
tags: [architecture, navigation, koin, viewmodel]
status: accepted
---

# Tasks feature → nested navigation3 graph

## Context

`TaskDetailScreen.kt` и связанные экраны (`TaskDetailViewHost`, `TaskCreateHost`,
`TaskList`, `TaskCreateContent`, `TaskDetailViewContent`) принимают
**ручные коллбеки** (`onBack`, `onNavigateToProject`, `onNavigateToTask`,
`onNavigateToCreateTask`). Это размывает ответственность: presentation знает
про иерархию экранов, а не только про состояние.

Дополнительно выявлен **скрытый баг VM scoping** (см. ADR `2026-09-14-nav3-vm-store-decorator-fix`,
который вводится вместе с этим рефакторингом): `koinViewModel { parametersOf(taskId) }`
внутри `entry<AppDestination.TaskDetail>` сейчас возвращает **один и тот же
instance `TaskDetailViewModel`** для разных taskId в рамках одной Activity —
`LocalViewModelStoreOwner` резолвится в `ComponentActivity`, а не в entry.
Этот баг латентен: пользователь видит task-A state под route task-B
после back+forward.

## Idea

1. Выделить tasks в **nested graph** с собственным
   `NavBackStack<TasksRoute>`, как в `philipplackner/Nav3Guide` (см.
   `NavigationRoot.kt` в hui.txt).
2. Внутри экраны читают `LocalTasksNavigator` (CompositionLocal) —
   без ручных коллбеков.
3. **Подключить `rememberViewModelStoreNavEntryDecorator`** на обоих уровнях
   (outer NavDisplay + inner TasksNavGraph), что чинит VM scoping
   для всех фич.
4. Расщепить View/Create на отдельные `TasksRoute.Detail` / `TasksRoute.Create`.

## Decision

### Nested NavHost

```kotlin
// feature/tasks/presentation/nav/TasksNavGraph.kt
@Composable
fun TasksNavGraph(
    start: TasksRoute,                       // не обязательно List
    onExitGraph: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backStack = rememberNavBackStack(
        configuration = SavedStateConfiguration {
            serializersModule = SerializersModule {
                polymorphic(NavKey::class) {
                    subclass(TasksRoute.Inbox::class, ...)
                    subclass(TasksRoute.Today::class, ...)
                    subclass(TasksRoute.ByProject::class, ...)
                    subclass(TasksRoute.Detail::class, ...)
                    subclass(TasksRoute.Create::class, ...)
                }
            }
        },
        start
    )
    val navigator = remember(backStack, onExitGraph) {
        TasksNavigator(backStack, onExitGraph)
    }

    CompositionLocalProvider(LocalTasksNavigator provides navigator) {
        // System back в корне nested-графа → onExitGraph (см. ADR-раздел "Back")
        BackHandler(enabled = backStack.size <= 1) { onExitGraph() }

        NavDisplay(
            backStack = backStack,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),   // ← чинит VM scoping
            ),
            onBack = { navigator.back() },
            entryProvider = tasksEntryProvider(),             // свой DSL внутри nav-пакета
        )
    }
}
```

`TasksNavigator` инкапсулирует `backStack: NavBackStack<TasksRoute>` (private),
экраны не имеют к нему прямого доступа. `LocalNavBackStack` НЕ экспортируется —
только `LocalTasksNavigator`.

### TasksRoute

```kotlin
sealed interface TasksRoute : NavKey {
    sealed interface List : TasksRoute { val projectId: ProjectId? }
    @Serializable data class Inbox(override val projectId: ProjectId? = null) : List {
        val filter: ListFilter get() = ListFilter.Inbox   // derived, not serialized
    }
    @Serializable data class Today(override val projectId: ProjectId? = null) : List {
        val filter: ListFilter get() = ListFilter.Today
    }
    @Serializable data class ByProject(override val projectId: ProjectId) : List {
        val filter: ListFilter get() = ListFilter.ByProject
    }
    @Serializable data class Detail(val taskId: TaskId) : TasksRoute
    @Serializable data class Create(val initialDueDate: LocalDate? = null) : TasksRoute
}
enum class ListFilter { Inbox, Today, ByProject }
```

**Round-trip `filter`**: `@Serializable` сериализует только constructor-параметры
(`projectId`). `filter` — derived val через getter, без backing field; после
`Json.decodeFromString` getter всё равно возвращает константу. Покрыто unit-тестом.

### AppDestination

```kotlin
@Serializable data class TasksGraph(
    val start: TasksStartRoute,
    val initialDueDate: kotlinx.datetime.LocalDate? = null,   // @Serializable напрямую
) : AppDestination
@Serializable data class TasksByProject(val projectId: String) : AppDestination

sealed interface TasksStartRoute : NavKey {
    @Serializable data object Inbox : TasksStartRoute
    @Serializable data object Today : TasksStartRoute
    @Serializable data object Create : TasksStartRoute
}
```

`TaskDetail` / `TaskDetailCreate` — удаляются (покрываются `TasksGraph(start=Create, initialDueDate=...)`
+ `TasksByProject`). `TasksByProject` остаётся отдельным top-level,
делегирующим в `TasksNavGraph(start = TasksRoute.ByProject(...))`.

### Routing vs domain в `TaskDetailIntent`

Удаляем `NavigateToProject` / `NavigateToTask` из `TaskDetailIntent` —
routing ответственность `TasksNavigator`, не VM. Domain интенты без изменений.

### VM events — оставляем SharedFlow + NotificationHost

`NotificationHost` уже инкапсулирует UI-side реакцию; VM events (Saved/Error/
UndoDelete) — легитимные domain-события, не navigation-result.
`LocalResultEventBus` + `ResultEffect<T>` — **future enhancement** в ADR,
не блокер текущей миграции.

### Back navigation

`TasksNavigator.back()`:
- `backStack.size > 1` → `backStack.removeLastOrNull()`.
- `backStack.size <= 1` → `onExitGraph()` (закрытие nested graph).

`BackHandler(enabled = backStack.size <= 1) { onExitGraph() }` — гарантирует
ту же семантику для системной кнопки back.

### `isDirty` guard

Переезжает из `TaskCreateHost` в `TaskCreateScreen`: подписка на
`BackHandler` и TopBar back делает `if (state.isDirty) showDiscard else
navigator.back()`. `TasksNavigator` не знает про VM state — guard в экране.

### VM scoping fix (ADR `2026-09-14-nav3-vm-store-decorator-fix`)

Подключаем `rememberViewModelStoreNavEntryDecorator` **в двух местах**:

1. **Outer NavDisplay**: расширяем декораторы в
   `Nav3State.toDecoratedEntries` (`Nav3State.kt:48-58`) —
   добавляем `rememberViewModelStoreNavEntryDecorator<NavKey>()`.
2. **Inner TasksNavGraph**: добавляем в список `entryDecorators` (см. код выше).

Это чинит `TaskDetailViewModel(taskId=A) → back → taskId=B` и
`ProjectDetailViewModel(projectId=X) → back → projectId=Y`. Для VM без
`parametersOf` (TasksViewModel, TaskCreateViewModel с no-arg factory
не существует — но, например, `ProjectsViewModel`) поведение остаётся
эквивалентным: один instance на NavEntry (а не на Activity) — это
правильно.

### Preview support

`LocalTasksNavigator` через `compositionLocalOf { error(...) }`. Preview
composables оборачивают в `CompositionLocalProvider(LocalTasksNavigator
provides PreviewTasksNavigator)` — no-op, логирует вызовы.

## Rationale

- **Feature isolation.** Tasks-фича переносима между shell-ами
  (Android/JVM/будущий iOS).
- **Type-safety.** Sealed `TasksRoute` + sealed `TasksStartRoute`,
  compiler-checked.
- **Минимизация boilerplate.** Убраны 4+ коллбеков из сигнатур экранов.
- **VM scoping correctness.** `rememberViewModelStoreNavEntryDecorator` —
  официальный путь nav3-recipes/passingarguments/koin. Без него
  `parametersOf(taskId)` — silent no-op.
- **Backwards-compatible.** 4 существующих entry-points покрываются
  без регрессии UX (Inbox/Today как top-level entries, делегирующие
  в TasksNavGraph).
- **Соответствует официальному recipe.** Структура идентична
  `philipplackner/Nav3Guide/NavigationRoot.kt` (см. hui.txt) —
  переносимый паттерн.

## Consequences

### Положительные

- ~18 файлов переработано, +5 новых, -2 удалено.
- Сигнатуры экранов tasks упрощаются до 1-2 аргументов.
- **Чинится латентный VM scoping bug** для `TaskDetailViewModel`,
  `TaskCreateViewModel`, `ProjectDetailViewModel` и всех остальных
  `koinViewModel { parametersOf(...) }` callsites (их в проекте ~20).
- Navigation между Detail и подзадачами/проектами становится
  type-safe (`TasksNavigator.openDetail(TaskId)`).

### Отрицательные

- Первая фича с nested graph — другие фичи (notes/projects/auth/settings)
  пока на плоском графе. Будущая постепенная миграция по аналогии.
- Дополнительный уровень индирекции для новых разработчиков: «где я?».
- Необходимо зарегистрировать `TasksRoute` в двух `SerializersModule`:
  - В `TasksNavGraph.kt` (для nested `rememberNavBackStack`).
  - В `JvmNav3State.kt` для `AppDestination.TasksGraph` /
    `TasksByProject` (если понадобится polymorphic AppDestination —
    сейчас `SavedStateConfiguration { }` пустой, см. ADR
    `2026-09-11-nav3-kmp-migration`, item #2).
- Unit-тесты навигации tasks требуют `Robolectric` или `composeRule` —
  не pure-Kotlin.

### Альтернативы, которые отклонены

- **Один плоский AppDestination без nested graph** — не даёт feature
  isolation, не убирает коллбеки полностью (всё ещё нужно прокидывать
  `nav.navigate(...)` в экраны).
- **`LocalNavBackStack` как публичный API** — позволяет экранам
  обходить семантику `TasksNavigator.back()` (size<=1 → onExitGraph).
  Оставлен internal.
- **`TasksRoute.Pop` как sentinel** — race condition (см. review rev. 1,
  пункт #1). Заменён на прямой вызов `onExitGraph` лямбды.
- **`String`-encoded `initialDueDate`** — заменён на
  `kotlinx.datetime.LocalDate?` (он `@Serializable`).

## Links

- `philipplackner/Nav3Guide` — `composeApp/src/commonMain/kotlin/com/plcoding/nav3_guide/navigation/NavigationRoot.kt` (см. hui.txt)
- `androidx.navigation3:nav3-recipes/multiple-backstacks`
- `androidx.navigation3:nav3-recipes/passingarguments/viewmodels/koin`
- `2026-09-11-nav3-kmp-migration.md` — базовая nav3 архитектура
- `2026-09-09-feature-tasks-clean-architecture.md` — структура feature/tasks
- `2026-09-09-task-detail-intent-refactor.md` — task detail intent split
