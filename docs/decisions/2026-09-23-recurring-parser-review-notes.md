---
title: "Recurring tasks — post-review findings, no-blocker"
date: 2026-09-23
tags: [recurring, tasks, review]
status: noted
---

## Context

After completing the recurring tasks implementation (MR-1 through MR-5, plus parser refactor), a code review identified several non-critical issues in `RecurrenceParser`, `RecurrenceCalculator`, and the test suite. None are blockers for merge.

## Findings

### 1. Parser — `+1x` silent fallback was a bug (fixed in `f77c91ca`)

**Was**: `timeUnit("x")` returned `null`, but `parseShortForm` returned `Interval(FROM_DUE, 1, DAY)` instead of `null`, silently consuming invalid input.

**Fixed**: `timeUnit(unit) ?: return null` — invalid unit causes the rule to fail, `parseRule` throws `IllegalArgumentException("Unrecognised token at position N")`.

**Impact**: Low. `+1x` now throws like `every 1x` and `++1x` already did. No silent data corruption.

---

### 2. Calculator — `countWeeklyMissed` is O(n) where n = missed occurrences

`RecurrenceCalculator.countWeeklyMissed` (and `countMonthlyMissed`, `countYearlyMissed`) use a loop:

```kotlin
while (true) {
    val next = nextWeekly(current, weekdays)
    if (next > today) break
    count++; current = next
}
```

For `CATCH_UP` mode with `MAX_MISSED = 10`, this is bounded to 10 iterations — not a practical concern. If `MAX_MISSED` ever grows, or for `countIntervalMissed` with large day counts, this could become slow.

**Potential improvement**: O(1) arithmetic for `Interval`:
```kotlin
val diff = today.toEpochDays() - anchor.toEpochDays()
count = diff / amount  // integer division truncates
```
Weekly/monthly/yearly are harder but could use `LocalDate.until()` arithmetic.

**Impact**: Low. MAX_MISSED=10 caps all loops. No action needed.

---

### 3. Calculator — `lastDayOfMonth` works but is obscure

`RecurrenceCalculator.lastDayOfMonth(2024, 12)` computes:
```kotlin
val nextMonthOrdinal = if (month == 12) 0 else month  // 0
val nextMonthYear = if (month == 12) year + 1 else year  // 2025
val firstOfNextMonth = LocalDate(2025, Month.entries[0], 1)  // Jan 1, 2025
return firstOfNextMonth.minus(1, DAY).day  // Dec 31, 2024 ✓
```

This works correctly but relies on wrapping January to the previous year. A cleaner version:
```kotlin
LocalDate(year, month, 1).plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY).day
```
Or even cleaner using `month.minLength` or a lookup table.

**Impact**: None. Results are correct. Refactor as a cleanup task if desired.

---

### 4. Tests — `CompleteRecurringTaskUseCase` has no dedicated tests

Only `RecurrenceParserTest` (27 cases) and `RecurrenceCalculatorTest` (14 cases) exist. `CompleteRecurringTaskUseCase` has no unit tests.

Integration coverage via `TaskDetailViewModelTest` may partially cover the path, but `invoke(task, spec)` private method branches (FROM_DUE / FROM_COMPLETION / CATCH_UP) are not independently tested.

**Impact**: Medium. The use case is the most complex piece (CATCH_UP creates N tasks in a loop). Should be tested before MR-13 final check.

---

### 5. Tests — `FakeTaskRepository` doesn't load `tags`/`dependsOn` into `Task` from extras

`TaskExtrasLoadingTest` validates that `observeAll()` returns tasks with `tags` and `dependsOn` populated — but it does so by seeding `Task` objects directly, bypassing `TaskEntity.toTask(tags, dependsOn)`.

Production flow: `TaskEntity` → `Mappers.toTask()` → `Task` with extras from `userTasksWithExtras`. The fake tests the domain contract (repository returns what it stores) but not the production `TaskEntity → Task` mapping with extras.

**Impact**: Medium. The `TaskExtrasLoadingTest` gives confidence that `tags` and `dependsOn` flow through the repository, but doesn't test the Room query / entity mapping. This is acceptable given the existing `TaskExtrasLoadingTest` covers the integration boundary.

---

### 6. UI — `RecurrenceFormatters.label` doesn't round-trip through `RecurrenceParser`

`RecurrenceParser.parse(RecurrenceFormatters.label(spec))` is not tested. The formatter produces labels like `"↑ Weekly(Mon,Wed,Fri)"` which are not valid parser input. This is intentional (labels are for display, not parsing), but worth documenting.

**Impact**: Low. Intentional design — display labels are not meant to be re-parsed.

---

## Decision

No blocking issues. All findings are noted for future cleanup.

## Consequences

- `CompleteRecurringTaskUseCaseTest` should be added before final merge (MR-13).
- `FakeTaskRepository` could gain `tags`/`dependsOn` population from a fake extras query, but is not blocking.
- `lastDayOfMonth` refactor is optional cleanup.

## Links

- `RecurrenceCalculator.kt` — pure calculator, all 14 test cases passing
- `RecurrenceParser.kt` — manual recursive-descent parser, all 27 test cases passing
- `CompleteRecurringTaskUseCase.kt` — no dedicated tests (see finding #4)
