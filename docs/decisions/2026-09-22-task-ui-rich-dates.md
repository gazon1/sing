---
title: "Task UI + Domain Wiring for Rich Dates (MR-2b)"
status: accepted
---

# Task UI + Domain Wiring for Rich Dates (MR-2b)

## Context

MR-2a added 6 new nullable fields to the Task schema (v16): `startDate`, `startTime`,
`endDate`, `endTime`, `accentColor`, `emoji`. MR-2b wires these fields through the domain
layer, use cases, ViewModels, and the Calendar mapper — without touching the Compose UI screens
(which is a separate concern for MR-3/MR-4).

## Decision

### Domain layer

**`TaskDomain.createInput`**: added 6 new parameters — `startDate`, `startTime`,
`endDate`, `endTime`, `accentColor`, `emoji` — passed through to `CreateTaskInput`.

**`TaskDomain.buildTask`**: passes all 6 new fields from `CreateTaskInput` to the `Task` constructor.

### `TaskComputed.isActive`

New derived predicate replacing the old hand-rolled checks:
```kotlin
fun isActive(task: Task, today: LocalDate): Boolean {
    if (task.isCompleted || task.isTrashed) return false
    val afterStart = task.startDate?.let { today >= it } ?: true
    val beforeEnd = task.endDate?.let { today <= it } ?: true
    return afterStart && beforeEnd
}
```

A task is **active** on a given day when:
- It is not completed and not trashed
- `today >= startDate` (or `startDate` is null)
- `today <= endDate` (or `endDate` is null)

This replaces `isReady` as the primary "should this task appear in agenda/calendar" filter.
`isReady` is retained for task-list filtering.

### Calendar mapper

**`CalendarTaskMapper.toCalendarTaskUi`**: `emoji` and `accentColor` are now mapped from the
task instead of being hardcoded `null`. KDoc comments on `CalendarTaskUi` are updated to
remove the "requires migration" notes.

### `TaskDraft` + `TaskCreateIntent`

`TaskDraft` (the serializable draft persisted via `DraftStore`) now carries:
```kotlin
val startDate: DueDateOption = DueDateOption.None
val startTime: LocalTime? = null
val endDate: DueDateOption = DueDateOption.None
val endTime: LocalTime? = null
val accentColor: Long? = null
val emoji: String? = null
```

New intents added to `TaskCreateIntent`:
- `SetStartDate`, `SetStartTime`
- `SetEndDate`, `SetEndTime`
- `SetAccentColor`, `SetEmoji`

`TaskCreateViewModel` handlers update the draft state accordingly.

### `CreateTaskFromDraftUseCase`

Resolves `startDate`/`endDate` from `DueDateOption` (same logic as `dueDate`) and passes
all 6 new fields to `TaskDomain.createInput`.

### Tests

- **`CalendarTaskMapperTest`**: `makeTask` helper updated to include all new Task fields
  (using named args for clarity). Emoji and accentColor tests changed from "always null"
  to "passed through from task".
- **`TaskComputedTest`**: 12 new tests covering `isActive`:
  - active with no start/end dates
  - false when completed or trashed
  - startDate in past/future
  - endDate in future/past
  - today within start-end window
  - only startDate set (endDate null)
  - only endDate set (startDate null)

## Rationale

- **No new concepts**: `startDate`/`endDate` semantics mirror the existing `dueDate`/`dueTime`
  pattern; `DueDateOption` reuse keeps the UX model consistent.
- **`isActive` as single source of truth**: replaces scattered inline checks; pure and
  testable without mocks.
- **Draft-preserving**: new fields are part of `TaskDraft` so they survive process death
  via `DraftStore` — same as title, description, priority.
- **Calendar chip enrichment**: the mapper now does the right thing for MR-3's
  visual improvements.

## Consequences

- `TaskDraft` serialization format changes — old drafts opened after upgrade will
  have null for the 6 new fields (graceful, no crash).
- `isActive` is a behavioral change from previous inline logic — tested thoroughly.
- `CreateTaskFromDraftUseCase` now takes a dependency on `DueDateOption` resolution
  for start/end dates (same pattern as dueDate, no new complexity).
- Compose UI for setting these new fields is not yet built — that's MR-3's scope.

## Links

- MR-2a ADR: `2026-09-22-task-rich-dates.md`
- MR-2b branch: `feature/calendar-mr1-kizitonwose`
- MR-3: Click-to-create BottomSheet + ReminderRepo in CalendarDeps
