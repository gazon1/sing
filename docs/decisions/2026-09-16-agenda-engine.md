---
title: "AgendaEngine: единый DSL-движок для list-вью задач (org-agenda style)"
date: 2026-09-16
tags: [agenda, tasks, dsl, architecture]
---

## Context

Приложение имеет 4 list-экрана с перекрывающейся фильтрацией:
- `Inbox` (не завершённые, не someday)
- `Today` (задачи на сегодня)
- `Upcoming` (будущие задачи)
- `ByProject` (задачи проекта)

Каждый экран — отдельный `Screen` + `ViewModel` со своим набором фильтров. Логика фильтрации дублируется между `TasksViewModel`, `UpcomingViewModel`, `CalendarViewModel`. Расширение (новые фильтры, новые пресеты) требует правки в 3+ местах.

org-mode / org-super-agenda решают эту проблему одним движком: **секции** = **именованные группы селекторов** над единым потоком задач.

## Idea

Варианты:
1. **Оставить как есть** — 4 экрана, дублирование логики. Просто, но не масштабируется.
2. **Один `TaskListScreen` с динамическим фильтром** — менять `TaskFilter` по тапу на tab. Меньше кода, но всё ещё не extensibility.
3. **DSL-движок AgendaEngine** (этот ADR) — единый поток задач → `AgendaEvaluator` (pure function) → секции → рендеринг. Пресеты — это `AgendaDefinition` (data class), не код экрана.

## Decision

Создать `feature/agenda/` — отдельный модуль с DSL, pure evaluator и UI.

### Архитектура

```
feature/agenda/
├── domain/
│   ├── model/
│   │   ├── AgendaDefinition    — data class (title, sections, layout)
│   │   ├── Section             — data class (name, order, selector, discard, baseFilter)
│   │   ├── AgendaLayout        — enum { ListFlat, ListGrouped, TimeGrid }
│   │   ├── Selector            — sealed interface (предикаты и комбинаторы)
│   │   ├── RelativeBucket      — enum { Today, ThisWeek, Overdue, NoDate, ... }
│   │   ├── AgendaBadge         — sealed interface (Overdue, DueToday, Pinned, ...)
│   │   ├── AgendaUiState       — sealed interface (Loading | Loaded | Error)
│   │   ├── AgendaIntent        — sealed interface (ViewSwitched | SectionToggled | TaskClicked)
│   │   └── AgendaUiEvent       — sealed interface (NavigateToTask)
│   └── logic/
│       ├── AgendaEvaluator      — pure: List<Task> × AgendaDefinition × today → List<RenderedSection>
│       └── AgendaPresets        — Inbox, Today, Upcoming, byProject, byTag, byDateRange
└── presentation/
    ├── viewmodel/AgendaViewModel
    ├── screen/AgendaScreen + AgendaContent (Slot API)
    ├── components/ (AgendaTopBar, SectionHeader, RowItem)
    └── nav/ (AgendaRoute, AgendaNavGraph expect/actual)
```

### Контракт DSL

```kotlin
// Selector — только предикаты, никаких метаданных секции
sealed interface Selector {
    data class DateBucket(val bucket: RelativeBucket) : Selector
    data class DateRange(val from: LocalDate, val to: LocalDate) : Selector
    data class Statuses(val statuses: Set<TaskStatus>) : Selector
    data class Tag(val id: TagId) : Selector            // одиночный; Set — MR2
    data class Projects(val ids: Set<ProjectId>) : Selector
    data object Pinned : Selector
    data object Completed : Selector
    data object Overdue : Selector
    data class AllOf(val children: List<Selector>) : Selector
    data class AnyOf(val children: List<Selector>) : Selector
    data class Not(val child: Selector) : Selector
    data object Anything : Selector
}

data class Section(
    val name: String,
    val order: Int = 0,
    val selector: Selector,
    val discard: Boolean = false,
    val baseFilter: TaskFilter? = null,   // SQL narrowing hint
)
```

### DSL-пример

```kotlin
val Today = agenda("today", "Today") {
    section("Overdue", order = 0) { Selector.Overdue }
        .withBaseFilter(TaskFilter.Today)
    section("Today", order = 1) {
        Selector.allOf(Selector.statuses(TaskStatus.Active), Selector.dateBucket(RelativeBucket.Today))
    }.withBaseFilter(TaskFilter.Today)
}
```

### Known limitations (будут закрыты отдельными MR)

- `today` фиксируется в VM при создании — не реактивный. Реактивный `todayFlow(clock)` — отдельный MR.
- `Selector.Tag` принимает одиночный `TagId`; `Set<TagId>` — MR2.
- `RelativeBucket` enum раздут — MR2 рефакторит на `Selector.DateRange` для абсолютных диапазонов.
- 3 разных `today()` в проекте (`LocalDate.fromEpochDays`, `todayInSystemZone()`) — унификация отдельным MR.

## Rationale

- **Extensibility**: новый пресет = новая `AgendaDefinition`, не новый экран/VM.
- **Testability**: `AgendaEvaluator` — pure function, 100% покрывается unit-тестами без моков.
- **Extensibility DSL**: комбинаторы `AllOf`/`AnyOf`/`Not` позволяют выражать произвольные булевы условия без кода.
- **Saved views**: `@Serializable` на `Selector`/`Section`/`AgendaDefinition` — JSON round-trip для будущих пользовательских пресетов.
- **Календарь переиспользует**: `CalendarViewModel` переходит на `AgendaPresets.byDateRange`, закрывая баг с stubbed `ByDateRange`.

## Consequences

- **Удаляются**: `UpcomingScreen`, `UpcomingViewModel`, `UpcomingUiState`, `TaskListScreen` (для Inbox/Today/ByProject), `TasksViewModel`, `TasksRoute.Inbox/Today/Upcoming/ByProject`, `AppDestination.Inbox/Today/Upcoming/TasksByProject`.
- **MR2**: `ByTags(set)`, `ByPriorities(set)`, `ByDateBucket` с SQL, `ByRegexp`, реактивный `todayFlow`, пользовательские saved views.
- **Нет saved views в v1**: пользовательские пресеты не сохраняются. Встроенные — захардкожены в `AgendaPresets`.
- **Экраны не под заменой**: `ProjectDetailScreen`, `NotesListScreen`, `SearchScreen`, `ArchiveScreen` — не agenda-вью.

## Links

- Следующий MR: имплементация AgendaEngine
- `feature/agenda/` — код модуля
- `feature/tasks/domain/model/TaskStatus.kt` — доменный enum (шаг 2 этого MR)
- `2026-09-16-task-list-filter-to-task-status.md` — сопутствующий ADR
