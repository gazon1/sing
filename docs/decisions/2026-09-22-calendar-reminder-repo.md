---
status: accepted
---

# Calendar + ReminderRepo Integration (MR-3a)

## Context

MR-3 calls for enriching the Calendar UI with recurring task indicators (⟳ icon) and
preparing for click-to-create task functionality. MR-3a focuses on the data layer:
wiring `ReminderRepository` into `CalendarDeps` so that `CalendarTaskUi.isRecurring` can be
populated correctly, and adding the `observeRecurringTaskIds` method needed for this.

## Decision

### `ReminderDao`: new query

```kotlin
@Query("SELECT DISTINCT task_id FROM task_reminders WHERE recurring_pattern IS NOT NULL AND user_id = :userId")
fun watchRecurringTaskIds(userId: String): Flow<List<String>>
```

Returns the set of task IDs that have at least one recurring reminder. Using `DISTINCT`
avoids N duplicate rows when a task has multiple recurring reminders.

### `ReminderRepository`: new method

```kotlin
fun observeRecurringTaskIds(): Flow<Set<TaskId>>
```

Returns a `Flow<Set<TaskId>>` — `Set` because a task may have multiple recurring
reminders but should only appear once in the calendar's recurring indicator.

### `RoomReminderRepository`: implementation

```kotlin
override fun observeRecurringTaskIds(): Flow<Set<TaskId>> =
    currentUser.observeForCurrentUser { uid ->
        dao.watchRecurringTaskIds(uid.value).map { list ->
            list.mapTo(linkedSetOf()) { TaskId.fromString(it) }
        }
    }
```

Uses `linkedSetOf()` for deterministic iteration order.

### `CalendarDeps`: new dependency

```kotlin
data class CalendarDeps(
    val taskRepo: TaskRepository,
    val reminderRepo: ReminderRepository,  // NEW
    val clock: kotlin.time.Clock = kotlin.time.Clock.System,
    val today: kotlinx.datetime.LocalDate,
    val logger: Logger,
)
```

### `CalendarViewModel`: state flow change

The state flow now combines `_calendarState` with `reminderRepo.observeRecurringTaskIds()`
_before_ the `flatMapLatest` on tasks:

```kotlin
val state: StateFlow<CalendarUiState> = combine(
    _calendarState,
    deps.reminderRepo.observeRecurringTaskIds(),
) { cal, recurringIds ->
    cal to recurringIds
}.flatMapLatest { (cal, recurringIds) ->
    deps.taskRepo.observeByFilter(...)
        .map { tasks ->
            tasks.map { task ->
                CalendarTaskMapper.toCalendarTaskUi(task, today, recurringIds.contains(task.id))
            }.groupBy { it.date }
        }
}
```

`isRecurring` is derived from `recurringIds.contains(task.id)`.

### `CalendarDiModule`: DI wiring

`ReminderRepository` is already available in the module via `get<ReminderRepository>()`.

## Rationale

- **`Set<TaskId>` not `List<TaskId>`**: a task with 3 recurring reminders should not appear
  as 3 separate IDs — the calendar icon is per-task.
- **`combine` before `flatMapLatest`**: the recurring IDs are a slow-changing signal
  (changes only on reminder create/delete), so combining them first avoids re-subscribing
  the task query on every reminder change.
- **`DISTINCT` in SQL**: reduces payload size vs filtering in-memory.
- **No per-task query**: avoids the N+1 problem of querying reminders for each task.

## Consequences

- `CalendarViewModel` now has an additional dependency — tests must inject
  `FakeReminderRepository` (already done in `CalendarViewModelTest`).
- `FakeReminderRepository` implements `observeRecurringTaskIds` using in-memory filtering.
- `FakeReminderDao` implements `watchRecurringTaskIds` for `FakeAppDatabase`.
- The ⟳ icon on calendar task chips will now work once MR-3b (Click-to-create)
  connects the full flow.

## Links

- MR-3b: Click-to-create BottomSheet + `EmptyCellLongPressed` intent
- `ReminderRepository` interface: `shared/src/commonMain/.../feature/reminders/ReminderRepository.kt`
