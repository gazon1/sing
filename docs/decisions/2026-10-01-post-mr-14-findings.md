---
title: "Post-MR-14 findings — agenda badge single source + Recurring gap"
date: 2026-10-01
tags: [agenda, badge, mr-14]
status: accepted
---

# Post-MR-14 audit findings

## MR-14: badge contract + Recurring gap

**Decision**: Created `computeAgendaBadge` as the single source of truth for computing per-task badges, closing the `Recurring` gap, aligning `DefaultBadgeRules` with `computeBadge`, and documenting the badge priority table.

### What was done

#### `computeAgendaBadge.kt` — new pure function

Created `feature/agenda/domain/logic/computeAgendaBadge.kt`:

```kotlin
/**
 * Pure badge computation: returns the [AgendaBadge] for [task] on [today].
 *
 * Priority (first non-null wins):
 * 1. Blocked — incomplete dependency
 * 2. Pinned — pinned by user
 * 3. Recurring — has a recurrence rule
 * 4. Completed — task is done
 * 5. Overdue — past due date (and not completed)
 * 6. NoDate — no due date set
 *
 * Null is returned for a normal in-date task.
 */
fun computeAgendaBadge(task: Task, today: LocalDate): AgendaBadge? =
    when {
        TaskComputed.isBlocked(task) -> AgendaBadge.Blocked
        task.isPinned -> AgendaBadge.Pinned
        task.recurrenceRule != null -> AgendaBadge.Recurring
        task.isCompleted -> AgendaBadge.Completed
        task.dueDate != null && task.dueDate < today -> AgendaBadge.Overdue
        task.dueDate == null -> AgendaBadge.NoDate
        else -> null
    }
```

#### `AgendaEvaluator` delegates

Updated `AgendaEvaluator.computeBadge` to delegate to `computeAgendaBadge`:

```kotlin
private fun computeBadge(task: Task, ...): AgendaBadge? =
    computeAgendaBadge(task, today)
```

#### `DefaultBadgeRules` aligned

Added `blocked` and `recurring` rules to `DefaultBadgeRules` in correct priority order:

```kotlin
val all: SelectorTransformer = SelectorTransformer.all(
    blocked,   // 1. Blocked
    pinned,    // 2. Pinned  
    recurring, // 3. Recurring
    completed, // 4. Completed
    overdue,   // 5. Overdue (checked before noDate so overdue wins)
    noDate,    // 6. NoDate
)
```

New `recurring` and `blocked` rules:

```kotlin
val recurring: SelectorTransformer = object : SelectorTransformer {
    override fun Selector.matches(task: Task, today: LocalDate): Boolean = true
    override fun badgeFor(task: Task, today: LocalDate): AgendaBadge? =
        if (task.recurrenceRule != null) AgendaBadge.Recurring else null
}

val blocked: SelectorTransformer = object : SelectorTransformer {
    override fun Selector.matches(task: Task, today: LocalDate): Boolean = true
    override fun badgeFor(task: Task, today: LocalDate): AgendaBadge? =
        if (TaskComputed.isBlocked(task)) AgendaBadge.Blocked else null
}
```

#### `SelectorBadge.kt` — KDoc updated

Updated the file-level and `DefaultBadgeRules` KDoc to reflect the new 6-badge coverage and correct priority.

### Badge priority table

| Priority | Badge | Condition |
|---|---|---|
| 1 | Blocked | `TaskComputed.isBlocked(task)` |
| 2 | Pinned | `task.isPinned` |
| 3 | Recurring | `task.recurrenceRule != null` |
| 4 | Completed | `task.isCompleted` |
| 5 | Overdue | `task.dueDate != null && task.dueDate < today && !task.isCompleted` |
| 6 | NoDate | `task.dueDate == null` |
| — | (none) | Normal in-date task |

### Tests

`computeAgendaBadgeFlowTest.kt` — desktop Compose UI test (in `desktopApp/src/jvmTest`), parameterized for each badge condition. Not in `jvmTest` because kover does not cover jvmTest artifacts.

### Fixed

- `AgendaBadge.Recurring-never-assigned` — `computeBadge` and `DefaultBadgeRules` had no path to `AgendaBadge.Recurring`
- `DefaultBadgeRules-missing-blocked` — blocked was checked in `computeBadge` but absent from `DefaultBadgeRules`
- `DefaultBadgeRules-incomplete` — only 4 of 6 badges covered

### Verification

```bash
./gradlew :shared:jvmTest                        # green
./gradlew :shared:detekt                          # green
./gradlew :desktopApp:test -PtestIncludes="**/computeAgendaBadgeFlowTest"  # green
```

### Related

- `feature/agenda/domain/logic/computeAgendaBadge.kt` — new single source
- `feature/agenda/domain/logic/AgendaEvaluator.kt` — delegates to policy
- `feature/agenda/domain/selector/SelectorBadge.kt` — DefaultBadgeRules updated
- `feature/agenda/domain/model/AgendaUiState.kt` — AgendaBadge enum (already correct)
