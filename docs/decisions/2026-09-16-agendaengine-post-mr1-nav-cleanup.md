---
title: "AgendaEngine MR1 post-cleanup: remove dead TasksRoute variants and deprecated AppDestination branches"
date: 2026-09-16
tags: [agenda, navigation, cleanup, deprecated]
---

## Context

После завершения MR1 AgendaEngine (`cea3877`) в кодовой базе остались:
- `TasksRoute` sealed interface с мёртвыми вариантами (`Inbox`, `Today`, `ByProject`, `Upcoming`), которые больше не имеют соответствующих экранов
- `TasksNavGraph` Android-специфичный `navSavedStateConfig` с регистрацией сериализаторов для удалённых вариантов
- `entry<AppDestination.TasksByProject>` в `AndroidNavEntries`/`JvmNavEntries`, монтирующий `TasksNavGraph.ByProject` — который был удалён
- `AppDestination.icon` с ветками `when`, всегда возвращающими `false` (`ProjectsStartRoute.Editor`, `NotesStartRoute`, `TasksStartRoute.Upcoming`)
- `AppDestination.Inbox/Today/Upcoming` и `TasksStartRoute.Inbox/Today/Upcoming/ByProject` без `@Deprecated` аннотаций

## Idea

Удалить мёртвый код и добавить deprecation-аннотации, чтобы:
1. Новые вызовы направлялись в `AgendaGraph` вместо старых экранов
2. Компилятор предупреждал при использовании устаревших API
3. Навигационные entries единообразно монтировали `AgendaNavGraph`

Варианты:
1. **Оставить как есть** — мёртвый код не вызывает рантайм-ошибок, но запутывает и создаёт техдолг
2. **Удалить мёртвое + добавить deprecations** (этот ADR) — чистый код, warnings при использовании старых API
3. **Полное удаление `Inbox/Today/Upcoming` из `AppDestination`** — breaking change для любых внешних ссылок; лучше сначала deprecate

## Decision

### 1. `TasksRoute` — удалены мёртвые варианты

```kotlin
@Serializable
sealed interface TasksRoute : NavKey {
    @Serializable data class Detail(val taskId: TaskId) : TasksRoute
    @Serializable data class Create(val initialDueDate: LocalDate? = null) : TasksRoute
}
```

Удалены: `List`, `Inbox`, `Today`, `ByProject`, `Upcoming`, `ListFilter`.

### 2. `toTasksRoute()` — fallback для deprecated вариантов

```kotlin
private fun AppDestination.TasksStartRoute.toTasksRoute(initialDueDate: LocalDate?): TasksRoute = when (this) {
    is AppDestination.TasksStartRoute.Create -> TasksRoute.Create(initialDueDate)
    is AppDestination.TasksStartRoute.Detail -> TasksRoute.Detail(TaskId.fromString(taskId))
    else -> TasksRoute.Create(initialDueDate)  // deprecated variants fall back
}
```

Fallback нужен для обратной совместимости: старые deep-links могут содержать `TasksGraph(Inbox/Today/etc.)`, но показывать хотя бы `Create`.

### 3. `TasksByProject` → `AgendaGraph` redirect

```kotlin
entry<AppDestination.TasksByProject> { route ->
    AgendaNavGraph(
        start = AgendaStartRoute.Project(route.projectId),
        onExitGraph = { dest ->
            when (dest) {
                is AppDestination.ProjectDetail -> nav.navigate(dest)
                else -> nav.goBack()
            }
        },
    )
}
```

Вместо `TasksNavGraph.ByProject` (удалён) — `AgendaNavGraph` с `AgendaStartRoute.Project`.

### 4. `@Deprecated` на `AppDestination` и `TasksStartRoute`

```kotlin
@Deprecated("Use AgendaGraph(AgendaStartRoute.Inbox) instead",
    replaceWith = ReplaceWith("AgendaGraph(AgendaStartRoute.Inbox)"))
data object Inbox : AppDestination { ... }

@Deprecated("Use AgendaGraph(AgendaStartRoute.Project(projectId)) instead",
    replaceWith = ReplaceWith("AgendaGraph(AgendaStartRoute.Project(projectId))"))
data class TasksByProject(val projectId: String) : AppDestination { ... }
```

`TasksStartRoute.Inbox/Today/Upcoming/ByProject` также помечены `@Deprecated` с `ReplaceWith("Create")`.

### 5. `AppDestination.icon` — удалены always-false ветки

Удалены:
- `is AppDestination.ProjectsStartRoute.Editor` — `ProjectsStartRoute` не является подтипом `AppDestination`
- `is AppDestination.NotesStartRoute` — аналогично
- `is AppDestination.TasksStartRoute.Upcoming` — вариант удалён

### 6. `Nav3SavedStateTest.kt` — переписан

Тесты NavBackStack переписаны для использования только `TasksRoute.Create` и `TasksRoute.Detail`.

## Rationale

- **Redirect** `TasksByProject → AgendaGraph` вместо поддержки отдельного NavGraph — устраняет дублирование навигационной логики
- **Fallback** в `toTasksRoute()` — обеспечивает graceful degradation для устаревших deep-links
- **Deprecate, не удалять** — `Inbox/Today/Upcoming` всё ещё нужны в `AppDestination` для `DestinationKind.tabs` и bottom bar; удаление сломает внешние ссылки

## Consequences

- Компиляция Android + JVM успешна, все тесты проходят
- Detekt: 263 findings (pre-existing), 0 в изменённых файлах
- `AgendaEngine MR1` полностью завершён

## Links

- MR1 commit: `cea3877 fix(tasks): correct UpcomingScreen 1970-01-01 fallback and stale docs`
- Plan: `.zcode/plans/plan-sess_16570ace-de32-435a-a3c7-6b09084cfefd.md`
