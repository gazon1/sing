---
title: "ADR 2026-09-16 — Reactive `todayFlow` for AgendaEngine"
status: accepted
---
# ADR 2026-09-16 — Reactive `todayFlow` for AgendaEngine

## Status

Accepted — MR2b implementation.

## Context

MR1 hard-codes `today` as `LocalDate.now()` in `AgendaViewModel` at construction time:

```kotlin
// MR1 — stable today for the lifetime of the ViewModel, but stale across midnight
class AgendaViewModel(deps: AgendaDeps, definition: AgendaDefinition, ...) : ViewModel() {
    private val today: LocalDate = todayInSystemZone() // captured once at init
}
```

This means:
- A user who keeps the Agenda screen open past midnight still sees "yesterday's" buckets.
- Calendar and Agenda can show inconsistent `today` values.
- The `Today` bucket silently becomes "yesterday" without any event.

## Decision

### `Clock.todayFlow()` — reactive date stream

A `Flow<LocalDate>` that emits the current date at midnight and at most once per day:

```kotlin
// commonMain/kotlin/com/singularity/todo/core/platform/Clock.kt
package com.singularity.todo.core.platform

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Emits the current [LocalDate] in [zone] and re-emits exactly once each calendar
 * midnight, then suspends until the next midnight. Uses [Clock] from kotlinx-datetime.
 *
 * The flow is cold — no polling occurs until a collector is active. Back-pressure
 * is handled naturally by [delay].
 *
 * @param zone The time zone used to compute midnight. Defaults to system default.
 */
fun Clock.todayFlow(zone: TimeZone = TimeZone.currentSystemDefault()): Flow<kotlinx.datetime.LocalDate> =
    flow {
        var current = now().toLocalDateTime(zone).date
        while (true) {
            emit(current)
            val nextMidnight = current.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
            delay(nextMidnight - now())
            current = now().toLocalDateTime(zone).date
        }
    }.distinctUntilChanged()
```

**Throttling strategy:** `delay(nextMidnight - now())` — suspends until exactly the next
midnight. This is vastly more efficient than 60-second polling (~1 emission/day vs ~1,440/day).

`distinctUntilChanged()` ensures that if multiple coroutines collect simultaneously (e.g. during
testing), only the first emission per date is processed.

### `AgendaViewModel` — reactive state

```kotlin
// MR2b — reactive: re-evaluates on user switch AND on date change
val state = combine(scopedUserId, clock.todayFlow()) { id, t -> id to t }
    .distinctUntilChanged()
    .flatMapLatest { (userId, currentToday) ->
        watchTasks(userId, TaskFilter.All)
            .map { tasks ->
                AgendaUiState.Loaded(
                    evaluate(tasks, definition, currentToday),
                    currentToday,
                )
            }
    }
    .stateIn(scope, SharingStarted.WhileSubscribed(5_000), AgendaUiState.Loading)
```

Key properties:
- `combine(userId, today)` — user switch or date change triggers re-evaluation.
- `distinctUntilChanged()` — suppresses re-evaluation if only the instant (not the date) changed.
- `flatMapLatest { (userId, currentToday) -> ... }` — user switch cancels in-flight evaluation.
- `currentToday` passed to `evaluate()` — bucket labels always accurate.

### `AgendaDeps` gains `clock: Clock`

```kotlin
data class AgendaDeps(
    val taskRepo: TaskRepository,
    val currentUser: ProfileAwareCurrentUser,
    val clock: Clock = Clock, // default for production; injectable for tests
    val logger: Logger,
)
```

Default `Clock` is the project singleton — tests can inject a test clock.

### `TaskFilter.ByDateBucket` — SQL-friendly bucket filter

```kotlin
data class ByDateBucket(
    val bucket: RelativeBucket,
    val today: LocalDate, // required to resolve relative ranges
) : TaskFilter
```

The `today` parameter is stored in the filter itself because SQL query dispatch happens
asynchronously. The `today` is captured at the moment of query construction.

### CalendarViewModel audit (bundled)

`CalendarViewModel` also uses `todayInSystemZone()` without reactive updates. The same
`clock.todayFlow().first()` pattern is applied:

```kotlin
// CalendarViewModel — MR2b
private val today: kotlinx.datetime.LocalDate by lazy {
    runBlocking { deps.clock.todayFlow().first() }
}
```

This gives a stable `today` for the lifetime of the ViewModel (same as MR1), but uses
the project's `Clock` for testability.

## Consequences

### Positive
- Agenda always shows correct bucket labels across midnight.
- User switch cancels in-flight evaluations cleanly.
- `Clock` injectable for deterministic tests via `runTest { advanceTimeBy(...) }`.
- Throttling prevents SQLite spam from polling.

### Negative
- `flatMapLatest` re-evaluates all tasks on every date change (necessary trade-off;
  SQL pre-filtering via `ByDateBucket` mitigates this for calendar screen).
- `delay(until-midnight)` means the flow never completes — collectors must be scoped
  appropriately.

### Neutral
- `ByDateBucket` requires `today` in SQL query dispatch — the filter is not purely
  declarative; this is an acceptable coupling since the `today` value is captured at
  query construction time and remains consistent within that emission cycle.
- Existing `AgendaDeps` binding must add `clock: Clock` parameter (no breaking change
  to existing call sites if default parameter is used).

## Alternatives considered

### 60-second polling
Rejected — ~1,440 emissions/day vs ~1. `delay(until-midnight)` is more efficient
and always correctly timed.

### `Flow<Instant>` emitting at midnight
More complex. The current approach is simpler and directly yields `LocalDate`.

### `Clock.todayFlow()` returning `StateFlow`
`StateFlow` has a replay buffer that would replay the last date on collection, which
is unnecessary for this use case. Plain `Flow` with `stateIn` at the ViewModel level
is the correct composition.
