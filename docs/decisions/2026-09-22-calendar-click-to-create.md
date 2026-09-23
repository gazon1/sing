---
status: accepted
date: 2026-09-22
title: Calendar — click-to-create task on long-press + ReminderRepository enrichment
---

## Context

MR-3 builds on MR-2a (Task schema v16 with startDate/endDate/accentColor/emoji) and MR-2b (Task UI for those fields). Two sub-tasks:

1. **MR-3a**: Inject `ReminderRepository` into `CalendarDeps` so `CalendarViewModel` can mark recurring tasks. Add `observeRecurringTaskIds(): Flow<Set<TaskId>>` to avoid per-task queries.
2. **MR-3b**: Long-press on an empty current-month cell → open task creation sheet pre-filled with that date.

## Decision

### MR-3a — `ReminderRepository` in `CalendarDeps`

**`ReminderDao`** gets a new query:
```kotlin
@Query("""
    SELECT DISTINCT task_id FROM task_reminders
    WHERE recurring_pattern IS NOT NULL AND user_id = :userId
""")
fun watchRecurringTaskIds(userId: String): Flow<List<String>>
```

**`ReminderRepository`** gets a new interface method:
```kotlin
fun observeRecurringTaskIds(): Flow<Set<TaskId>>
```

Returns `Set<TaskId>` (not `List`) — dedup since Room `DISTINCT` still returns duplicates across rows, and `Set` is the natural type for membership checks.

**`CalendarDeps`** adds `reminderRepo: ReminderRepository`.

**`CalendarViewModel`** state flow changes from:
```kotlin
_calendarState.flatMapLatest { cal -> tasksFlow(cal.visibleDates) }
```
to:
```kotlin
combine(_calendarState, reminderRepo.observeRecurringTaskIds()) { cal, recurringIds ->
    cal to recurringIds
}.flatMapLatest { (cal, recurringIds) ->
    tasksFlow(cal.visibleDates).map { tasks ->
        tasks.map { task ->
            task.toCalendarTaskUi(today, isRecurring = recurringIds.contains(task.id))
        }
    }
}
```

`combine` before `flatMapLatest` avoids re-subscribing the task query whenever the reminder set changes.

### MR-3b — Click-to-create on long-press

**New intent:**
```kotlin
data class EmptyCellLongPressed(val date: LocalDate) : CalendarIntent
```

**New UI event:**
```kotlin
data class ShowCreateTaskSheet(val initialDueDate: LocalDate) : CalendarUiEvent
```

**`CalendarNavigator.openCreateTask(date)`** navigates to the outer `TasksGraph`:
```kotlin
open fun openCreateTask(initialDueDate: LocalDate) {
    onExitGraph(
        AppDestination.TasksGraph(
            start = AppDestination.TasksStartRoute.Create,
            initialDueDate = initialDueDate,
        ),
    )
}
```

**`MonthDayCell`** uses `combinedClickable` with a guard:
```kotlin
val hasNoTasks = tasks.isEmpty() && isCurrentMonth
Box(
    modifier = modifier
        .combinedClickable(
            onClick = onClick,
            onLongClick = if (hasNoTasks) { { onEmptyCellLongPress(date) } } else { {} },
        )
) { ... }
```

Long-press is only active on empty cells in the current month. Tasks existing on other months/days do not block long-press — the guard is purely `tasks.isEmpty()`.

**`TaskCreateViewModel`** receives `initialDueDate: LocalDate?` via `parametersOf` and seeds the draft's `dueDate`.

## Consequences

- `ReminderRepository` is now a dependency of `CalendarViewModel` — tested via `FakeReminderRepository` in `CalendarViewModelTest`.
- `CalendarDeps` is constructed in `CalendarDiModule` via `get<ReminderRepository>()`.
- `ShowError` event removed from `CalendarUiEvent` (no longer needed after previous refactors).
- `CalendarNavigator` gets two `onExitGraph` callers: `openTask` and `openCreateTask`.
- `TasksStartRoute.Create` now accepts `initialDueDate` — backward compatible since it's nullable.

## Links

- MR-3a implementation: `CalendarDeps`, `ReminderRepository`, `CalendarViewModel`, `CalendarDiModule`
- MR-3b implementation: `CalendarIntent`, `CalendarUiEvent`, `CalendarNavigator`, `CalendarScreen`, `CalendarContent`, `MonthGridView`
