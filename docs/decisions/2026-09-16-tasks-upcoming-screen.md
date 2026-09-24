---
Key constraints: 
title: "Tasks Upcoming Screen"
status: accepted
---

## Decision

### 1. New narrow repository method

Add `watchTasksByDate(userId, date)` to `TaskRepository` port and implementation.
This isolates the use case without polluting the `TaskFilter` sealed hierarchy:

```kotlin
// TaskRepository.kt
fun watchTasksByDate(userId: UserId, date: kotlinx.datetime.LocalDate): Flow<List<Task>>

// TaskRepositoryImpl.kt
override fun watchTasksByDate(userId: UserId, date: LocalDate): Flow<List<Task>> =
    taskDao.watchByDate(userId.value, date.toString())
        .map { list -> list.map { it.toTask() } }
```

The existing `taskDao.watchByDate` query is reused — no new Room queries needed.

### 2. Upcoming tab replaces Statistics in bottom bar

`DestinationKind.tabs` order: `Inbox, Today, Upcoming, Plans, Pomodoro`.
`Statistics` moves to `DestinationKind.menuEntries`.

### 3. Recurring label computed in UI mapper

```kotlin
object UpcomingTaskUiMapper {
    fun toUpcomingTaskUi(task: Task, today: LocalDate): UpcomingTaskUi {
        val recurring = due?.let { labelFor(it, today) }
        // ...
    }

    private fun labelFor(due: LocalDate, today: LocalDate): String = when (due) {
        today         -> "Today"
        today + 1day  -> "Tomorrow"
        else          -> "${due.dayOfMonth} ${monthNames[due.month.ordinal]} ${due.year}"
    }
}
```

### 4. First day of week isolated in pure object

```kotlin
internal object UpcomingFirstDayOfWeek {
    fun of(date: LocalDate): LocalDate {
        val daysFromMonday = (date.dayOfWeek.ordinal + 6) % 7
        return date.minus(daysFromMonday, DateTimeUnit.DAY)
    }
}
```

### 5. `deadlineDate` badge field

`TaskBadgesUi.deadlineDate = task.dueDate` is populated and rendered in
`UpcomingBadges` as a red flag icon plus a formatted `mm/dd/yyyy` date for
tasks due on the selected date.

### 6. Autorenew → Repeat global unification

`Icons.AutoMirrored.Filled.Autorenew` was replaced with `Icons.Default.Repeat`
everywhere in the codebase. The `Repeat` icon is the project standard for
recurring items.

---

## Consequences

### Positive
- `TaskFilter` remains untouched — Search feature is unaffected.
- Single narrow Room query (`watchByDate`) reused for the new use case.
- Pure `UpcomingTaskUiMapper` and `UpcomingFirstDayOfWeek` are unit-testable
  without Compose or Koin.
- Week-start locale handling is isolated and can be made configurable later.

### Negative
- `deadlineDate` badge is rendered as a red flag + date for tasks due on the selected date.
- `Upcoming` tab position (3rd) shifts the bottom bar order — snapshot tests
  referencing `DestinationKind.tabs` will break.

### Deferred
- Locale-aware `firstDayOfWeek` (hardcoded to Monday for MVP).
- Deadline indicator rendering in `UpcomingBadges`.
- Week navigation via swipe on `DaySwitcherRow`.

---

## Superseded

> **Superseded by [2026-09-16-agenda-engine.md](./2026-09-16-agenda-engine.md)**
> The `UpcomingScreen` and its `UpcomingViewModel` were deleted as part of the
> AgendaEngine MR1. The Upcoming agenda view is now provided by
> `AgendaNavGraph(start = AgendaStartRoute.Upcoming)`, which evaluates
> `AgendaPresets.Upcoming` over `TaskFilter.All` via `AgendaEvaluator`.
> `TaskRepository.watchTasksByDate` is replaced by `TaskFilter.ByDateRange`
> (implemented in Step 4 of AgendaEngine MR1).
> Files deleted: `UpcomingScreen.kt`, `UpcomingViewModel.kt`, `UpcomingUiState.kt`,
> `UpcomingTaskUiMapper.kt`, `DaySwitcherRow.kt`, `UpcomingTopBar.kt`,
> `UpcomingTaskRow.kt`, `UpcomingBadges.kt`.

## Links

- `UpcomingViewModel`, `UpcomingScreen`, `UpcomingTaskRow` — implementation (deleted)
- `TaskRepository.watchTasksByDate` — replaced by `TaskFilter.ByDateRange`
- `docs/decisions/DIGEST.md` — updated with this decision
