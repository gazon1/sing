---
date: 2026-09-16
status: accepted
tags: [calendar, feature, navigation]
---

# Calendar feature — data layer, UI modes, view models

## Context

The app needs a full calendar screen with multiple view modes. Key constraints:

- **kotlinx-datetime 0.8.0 limitations:** No `LocalDate.plus(1, DateTimeUnit.MONTH)`,

## Decision

### 1. Data Layer: `TaskFilter.ByDateRange`

Extends `TaskFilter` sealed interface with a range-based filter:

```kotlin
data class ByDateRange(
    val from: kotlinx.datetime.LocalDate,
    val to: kotlinx.datetime.LocalDate,
) : TaskFilter
```

`TaskDomain.matchesFilter` handles it:
```kotlin
is TaskFilter.ByDateRange -> task.dueDate != null &&
        task.dueDate >= filter.from && task.dueDate <= filter.to && !task.isTrashed
```

`TaskRepositoryImpl` returns `flowOf(emptyList())` for `ByDateRange` — the
`CalendarViewModel` handles it via `flatMapLatest` with `watchTasks`.

`FakeTaskRepository.watchTasks` filters in-memory using the same `matchesFilter`.

### 2. Domain Models

```kotlin
enum class CalendarViewMode { DAY, FOUR_DAYS, WEEK, MONTH }

enum class CalendarTaskStatus { PENDING, DONE, OVERDUE }

data class CalendarTaskUi(
    val id: TaskId, val title: String, val date: LocalDate,
    val isAllDay: Boolean = true, val startTime: LocalTime? = null,
    val endTime: LocalTime? = null, val status: CalendarTaskStatus = PENDING,
    val isRecurring: Boolean = false, val isLink: Boolean = false,
    val emoji: String? = null, val accentColor: Long? = null,
)
```

### 3. Pure Date Arithmetic (`CalendarDateMath.kt`)

All date logic is side-effect-free and unit-tested:
- `monthGridDates(anyDateInMonth)` → 42 dates (6 weeks, Mon-anchored)
- `visibleRange(anchor, viewMode)` → date range per mode
- `goNext / goPrevious` → navigation
- `headerLabel(anchor, viewMode)` → display string
- `firstDayOfMonth / lastDayOfMonth`

Month arithmetic (no `plus(1, DateTimeUnit.MONTH)`):
```kotlin
val nextMonthOrdinal = date.month.ordinal + 1
val nextMonth = if (nextMonthOrdinal == 12) Month.JANUARY
                else Month.entries[nextMonthOrdinal]
```

### 4. `CalendarTaskMapper`

Transforms `Task → CalendarTaskUi`:
- `isCompleted → DONE`
- `dueDate < today && !completed → OVERDUE`
- `dueDate == today → PENDING`
- `isAllDay = task.dueTime == null`
- `startTime = task.dueTime`
- `isLink = task.kind == TaskKind.Note`

### 5. Theme: `LocalCalendarPalette`

```kotlin
data class CalendarPalette(...)
val LocalCalendarPalette = staticCompositionLocalOf<CalendarPalette>

@Composable fun ProvideCalendarPalette(content) { ... }
```

Dark palette uses hardcoded hex values matching reference screenshots.
Light palette derives from `MaterialTheme.colorScheme`.

### 6. Navigation: Nested nav3 Graph

```
AppDestination.Calendar (6th tab, icon CalendarMonth)
  └─ CalendarNavGraph (expect/actual)
       └─ CalendarRoute: Month(anchor) | Day(anchor)
```

Task click → `AppDestination.TasksGraph(TasksStartRoute.Detail(taskId.value))`.
Calendar start route serializable: `CalendarStartRoute.Month(anchor)`.

Platform-specific back stack:
- **Android:** `navSavedStateConfig` + `rememberViewModelStoreNavEntryDecorator` + `BackHandler`
- **JVM:** `rememberInMemoryNavBackStack` (no SavedStateConfig)

### 7. ViewModel

```kotlin
class CalendarViewModel(
    deps: CalendarDeps(taskRepo, currentUser, clock, logger),
    initialDate: LocalDate,
    initialMode: CalendarViewMode = MONTH,
    scopeOverride: CoroutineScope? = null,
)
```

State flow:
```kotlin
currentUser.scopedUserId
    .flatMapLatest { userId ->
        _calendarState.flatMapLatest { cal ->
            taskRepo.watchTasks(userId, TaskFilter.ByDateRange(from, to))
                .map { tasks ->
                    tasks.map { CalendarTaskMapper.toCalendarTaskUi(it, today) }
                        .groupBy { it.date }
                }
        }
    }
    .stateIn(scope, SharingStarted.WhileSubscribed(5_000), CalendarUiState.Loading)
```

`ByDateRange` query window extended ±7 days for month preloading.

### 8. Components

| Component | Notes |
|---|---|
| `CalendarTopBar` | Header label + view mode dropdown + today/mini buttons |
| `MonthGridView` | 6×7 grid with task chips, today/selected highlighting |
| `TimeGridView` | 24-hour `LazyColumn`, all-day strip, day headers |
| `MiniCalendarPanel` | Slide-in from right, day picker |
| `TaskChip` | Coloured chip with title, recurring icon, emoji support |
| `ViewModeDropdown` | Segmented button-style dropdown for Day/4d/Week/Month |

### 9. DI

```kotlin
fun calendarModule(): Module = module {
    viewModel { (year: Int, month: Int, mode: CalendarViewMode) ->
        CalendarViewModel(
            deps = CalendarDeps(taskRepo = get(), currentUser = get(),
                               clock = get(), logger = Logger.withTag("Calendar")),
            initialDate = LocalDate(year, month, 1),
            initialMode = mode,
        )
    }
}
```

`calendarModule()` added to `domainModule()` in `Modules.kt`.

---

## Consequences

### Positive
- No new repository or DAO methods — `ByDateRange` filter reuses existing `watchTasks`.
- Pure date arithmetic fully unit-tested with no Compose or Koin dependencies.
- Slot-API (`CalendarContent` separate from `CalendarScreen`) enables preview without Koin.
- `LocalCalendarPalette` isolates calendar theming without breaking `MaterialTheme`.
- Nested nav3 graph keeps task-click navigation encapsulated.

### Negative
- `isRecurring` is always `false` in `CalendarTaskUi` — requires per-task
  `ReminderRepository` lookup which would be N queries.
- `startAt`/`endAt`/`allDay` fields don't exist in the `Task` domain model
  (Room migration needed).
- `weight` modifier requires careful structuring inside `Row { Column(weight) }`.

### Deferred
- Horizontal swipe between dates.
- `startAt`/`endAt`/`allDay`/`recurrence` in `Task` (Room migration).
- Expand-day-list (tap day in month view to show all tasks).
- Full filter panel with Project / Tags / Priority / Status.
- Locale-aware first day of week.
- `deadlineDate` badge rendering in month grid.

---

## Links

- `feature/calendar/` — all feature source files
- `CalendarViewModel`, `CalendarDeps`, `CalendarContent`, `CalendarScreen`
- `CalendarNavGraph`, `CalendarRoute`, `CalendarNavigator`, `LocalCalendarNavigator`
- `CalendarDateMath`, `CalendarTaskMapper`, `CalendarPalette`
- `TaskFilter.ByDateRange`, `TaskDomain.matchesFilter`
- `docs/decisions/DIGEST.md`
