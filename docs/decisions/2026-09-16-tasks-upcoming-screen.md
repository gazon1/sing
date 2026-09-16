# ADR: UpcomingScreen — Tasks by User-Selected Date

**Date:** 2026-09-16
**Status:** Accepted
**Context:** Implementing a 6th bottom-bar tab ("Upcoming") that shows tasks
scheduled for a user-selected day, reusing Today logic, with a horizontal
7-day switcher.

---

## Context

The Today tab shows tasks with `dueDate == today`. Users requested the ability
to view tasks for any arbitrary date — past or future — without changing the
date context for the whole app.

Key constraints:
- `TaskFilter.Upcoming` is actively used in `FilterChipsRow.kt` (lines 30, 53, 79)
  for the Search feature. It **cannot** be deleted or repurposed.
- The `Task` domain model has **no `isRecurring` field**. Recurring labels
  (Today / Tomorrow / absolute date) are computed in the UI mapper from
  `dueDate` vs the current date.
- `ProfileAwareCurrentUser.today()` does not exist. `Clock` is injected instead.
- `java.util.Locale` is not KMP-portable. Week-start calculation hardcodes
  `DayOfWeek.MONDAY` for MVP.
- `Icons.Filled.CalendarViewWeek` is not available in material-icons-core.
  `Icons.Outlined.DateRange` is used instead.

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

## Links

- `UpcomingViewModel`, `UpcomingScreen`, `UpcomingTaskRow` — implementation
- `TaskRepository.watchTasksByDate` — new narrow method
- `docs/decisions/DIGEST.md` — updated with this decision
