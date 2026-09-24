---
title: "Recurring tasks — Orgzly/Tasks.org DSL, rolling completion, CATCH_UP"
date: 2026-09-23
tags: [recurring, tasks, dsl]
status: accepted
---

## Context

Tasks need to repeat: daily standups, weekly reviews, monthly retrospectives, yearly reviews. Users expect the same recurrence semantics they know from Orgzly/Tasks.org: `+1w` (weekly from due), `++1w` (weekly from completion), `!+1w` (catch-up), `every Mon/Wed/Fri`, `1st of month`.

Three orthogonal dimensions:
1. **Frequency**: daily, weekly (on weekdays), monthly (day-of-month), yearly (month + day)
2. **Base (anchor)**: FROM_DUE, FROM_COMPLETION, CATCH_UP
3. **Counting**: one-row rolling vs. N historical copies

## Idea

- Store `RecurrenceSpec` as a JSON string in a `recurrence_rule TEXT` column.
- Use a `sealed class` hierarchy with an `abstract val base: RecurrenceBase` — the base type is the same across all variants, enabling `when (spec.base)` exhaustiveness.
- `CompleteRecurringTaskUseCase` dispatches on `spec.base`:
  - FROM_DUE → `task.dueDate = nextOccurrence(spec, task.dueDate ?? today)`
  - FROM_COMPLETION → `task.dueDate = nextOccurrence(spec, completedAt.toLocalDate())`; also clears `completedAt` so the task reappears as due
  - CATCH_UP → generate up to `MAX_MISSED=10` historical copies for missed occurrences, roll the current task forward
- `RecurrenceCalculator` is a pure `object` — no state, no I/O, fully unit-testable without mocks.
- DSL parser supports both Orgzly short form (`+1w`, `++1w`, `!+1w`) and human-readable long form (`every Monday`, `every 2 weeks`, `1st of month`).

## Decision

**RecurrenceSpec sealed class hierarchy:**

```kotlin
@Serializable sealed class RecurrenceSpec {
    abstract val base: RecurrenceBase
    @Serializable enum class RecurrenceBase { FROM_DUE, FROM_COMPLETION, CATCH_UP }
    data class Interval(override val base: RecurrenceBase, val amount: Int, val unit: DateTimeUnit.DateBased) : RecurrenceSpec()
    data class Weekly(override val base: RecurrenceBase, val weekdays: Set<Int>) : RecurrenceSpec()
    data class Monthly(override val base: RecurrenceBase, val dayOfMonth: Int) : RecurrenceSpec()
    data class Yearly(override val base: RecurrenceBase, val month: Int, val day: Int) : RecurrenceSpec()
    companion object { const val MAX_MISSED = 10 }
}
```

**Serialization**: `StableJson.encodeToString(RecurrenceSpec.serializer(), spec)` → `recurrence_rule TEXT` column. Room auto-migrates the column; no custom TypeConverter needed.

**DateTimeUnit**: Use `kotlinx.datetime.DateTimeUnit.DateBased` (the companion objects `DateTimeUnit.DAY/WEEK/MONTH/YEAR` typed as `DateTimeUnit.DateBased`), NOT `DateTimeUnit.DateTimeUnitIndividual` which does not exist in this version.

**CompleteRecurringTaskUseCase** is `open class` to allow test subclassing with a stub override. Registered in `TasksDiModule` as `factory { CompleteRecurringTaskUseCase(get(), get(), get(), get()) }` — repo, clock, timezone, calculator.

**TaskDetailViewModel.ToggleComplete**: if `current.recurrence != null`, delegate to `completeRecurring(current.id)` instead of the plain toggle.

**TaskDomain.createInput/buildTask**: both now accept and propagate `recurrence: RecurrenceSpec? = null`.

## Rationale

**sealed class over sealed interface**: `abstract val base: RecurrenceBase` on the base class enables smart-cast access in `when` expressions without casts. A sealed interface cannot declare abstract properties.

**Single-row rolling over N copies for FROM_DUE/FROM_COMPLETION**: keeps the UI simple (one task in the list), matches Apple Reminders / TickTick behavior. CATCH_UP generates copies because the user explicitly wants to see filled-in history.

**MAX_MISSED = 10 cap**: prevents accidental explosion of generated tasks if the user goes on vacation and opens the app months later. CATCH_UP users can manually create historical copies if needed.

**Pure RecurrenceCalculator**: no `Clock` injection, no I/O. All date arithmetic is deterministic and testable with `FakeClock` in tests.

## Alternatives Considered

- **RRULE (iCalendar)**: over-engineered for this app's needs; hard to parse and emit correctly. Our DSL is user-visible only in the editor; the stored form is JSON.
- **`DateTimeUnit.DateTimeUnitIndividual`**: does not exist in kotlinx-datetime 0.6.x. The correct type for `DateTimeUnit.DAY` etc. is `DateTimeUnit.DateBased`.
- **Separate `recurrence_mode` column**: would require a separate enum column and more migration surface. JSON in one column is sufficient.

## Consequences

- `Task.recurrence: RecurrenceSpec?` — must be propagated through `CreateTaskInput`, `TaskDomain.createInput`, `TaskDomain.buildTask`, `CreateTaskUseCase`, `CreateTaskFromDraftUseCase`.
- `TaskDetailViewModel` now depends on `CompleteRecurringTaskUseCase` in `TaskDetailDeps`.
- Migration 18→19 adds `recurrence_rule TEXT NOT NULL DEFAULT NULL`.
- MCP server `create_task`/`update_task` tools need schema updates (deferred to post-MR-10 issue).

## Links

- [Orgzly repeat syntax](https://orgmode.org/manual/Repeated-tasks.html)
- [Tasks.org repeat](https://tasks.org/docs/reference/repeat)
- MR-1: RecurrenceSpec + Parser + Calculator + Formatter
- MR-3: Task.recurrence + CompleteRecurringTaskUseCase
- MR-4: RecurrencePickerSheet + VM intent
