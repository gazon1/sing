---
title: startDate vs dueDate — Task Date Model Semantics
status: deferred
status-was: pending  # non-vocabulary value, normalized 2026-10-05
deciders: product owner
impact: high
date: 2026-10-01
---

## Context

`Task` has two separate date fields:

```kotlin
val startDate: LocalDate?    // when task becomes active ("start on...")
val dueDate: LocalDate?      // when task is due ("due on...")
```

Current behavior:

| Scenario | `startDate` set | `dueDate` set | Agenda / Calendar display |
|---|---|---|---|
| Both set | ✓ | ✓ | `startDate` → `dueDate` range |
| Only `startDate` | ✓ | ✗ | Appears in NoDate bucket |
| Only `dueDate` | ✗ | ✓ | Appears in `dueDate` bucket |
| Neither | ✗ | ✗ | NoDate bucket |

`TaskComputed.isActive` considers both fields:

```kotlin
fun isActive(task: Task, today: LocalDate): Boolean {
    if (task.isCompleted || task.isTrashed) return false
    val afterStart = task.startDate?.let { today >= it } ?: true
    val beforeEnd = task.endDate?.let { today <= it } ?: true
    return afterStart && beforeEnd
}
```

Note: `endDate` is separate from `dueDate`. A task with `startDate = Oct 5`,
`dueDate = Oct 10`, `endDate = Oct 15` is considered "active" through Oct 15
even though it was "due" on Oct 10.

## Problem

The product decision on what a task with `startDate` but **no** `dueDate` means
has not been made. This is a functional specification gap.

**Candidate interpretations:**

1. **"Scheduled start"** — task becomes active on `startDate`. If no `dueDate`,
   it floats indefinitely after that date. The agenda should show it under
   a "Starts today" section or in the NoDate bucket. Current behavior matches
   this interpretation but NoDate is semantically wrong.

2. **"Soft deadline"** — `startDate` is a suggested start; `dueDate` is the
   hard deadline. If only `startDate` is set, the task is "not yet actionable"
   before that date and becomes "actionable but not due" after. Agenda could
   show it under a "Ready" or "Backlog" section separate from NoDate.

3. **"Task with only startDate = no dueDate is invalid"** — enforce that a task
   must have `dueDate` if it has `startDate`, or vice versa. Validation error
   on creation / update.

4. **"Someday/Maybe"** — a task with `startDate` but no `dueDate` is a someday
   maybe. NoDate bucket is the correct home. This conflicts with interpretation 1.

## Decision

**Pending product decision.** The code supports any of the above interpretations
without changes — only the *labeling* in the agenda UI and the test coverage
need updating.

Until decided, the following test documents the current (ambiguous) behavior:

```
// AgendaEvaluatorTest: task with startDate but no dueDate → NoDate bucket
// This is the current behavior, not the intended behavior.
```

## Consequences

- **Before product decision:** No code changes. The ADR tracks the question.
- **After product decision:** One or more of:
  - Agenda evaluator section label changes
  - `NoDate` bucket renamed to reflect semantics
  - New selector / bucket for "has startDate but no dueDate"
  - Validation rule added to `TaskValidator`
  - Test coverage added to `AgendaEvaluatorTest` and `TaskValidatorTest`

## Links

- `Task` model — `shared/src/commonMain/.../tasks/domain/model/Task.kt`
- `TaskComputed.isActive` — `shared/src/commonMain/.../tasks/domain/logic/Computed.kt`
- `AgendaEvaluator` bucket logic — `shared/src/commonMain/.../agenda/domain/logic/AgendaEvaluator.kt`
- `AgendaEvaluatorTest` — `shared/src/commonTest/.../agenda/domain/AgendaEvaluatorTest.kt`
