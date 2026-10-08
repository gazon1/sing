---
title: Recurrence rolls a task forward in place, so there is no duplicate to prevent
date: 2026-10-06
tags: [recurrence, sync, tasks, data-model]
status: accepted
---

## Context

A proposal arrived to make recurring task generation idempotent across devices. It described
duplicates like this:

> `RecurrenceJob` runs locally on every device. If Android and Desktop generate occurrences at the
> same time, two entities are created. `per-field LWW` does not help: they are different entities
> with different `TaskId`s. The user sees two recurring occurrences instead of one.
>
> The fix is a deterministic `TaskId` derived from `parentTaskId + dueDate`, plus a
> `UNIQUE (parent_task_id, due_date)` index as a race guard.

It scheduled that work first, as a blocker for everything else, at an estimated two days.

It is describing a bug this codebase cannot produce, and the proposed index would damage data.

## What completion actually does

`CompleteRecurringTaskUseCase` does not create a new task per occurrence. It mutates the task it was
given, under its existing id:

```kotlin
// FROM_DUE — feature/tasks/domain/usecase/CompleteRecurringTaskUseCase.kt
rollForward(task, spec, calculator.nextOccurrence(spec, task.dueDate ?: today), now, completeIt = true)
```

All three bases — `FROM_DUE`, `FROM_COMPLETION` and `CATCH_UP` — funnel into `rollForward`. The task
keeps its `TaskId`; only `dueDate`, `completedAt` and `updatedAt` change.

So two devices completing the same recurring task at the same moment produce **two updates to one
row**, not two rows. Under the conflict policy decided in
[2026-10-04-per-field-lww-conflict-policy](2026-10-04-per-field-lww-conflict-policy.md), the `dueDate`
field is applied independently and ordered by hybrid logical clock. One device's roll-forward wins.
There is nothing to deduplicate, because there was never a second entity.

The only place that *does* mint a new id is `CATCH_UP` historical copies:

```kotlin
val historicalTask = task.copy(id = TaskId.generate(), completedAt = now, dueDate = currentAnchor, ...)
repo.create(historicalTask)
```

That is a real, narrow race — the same task completed twice within the sync window on two devices,
with missed occurrences outstanding. It is not the multi-device storm the proposal described, and it
is bounded by `RecurrenceSpec.MAX_MISSED` (10) rather than unbounded.

There is no `RecurrenceJob`. Recurrence is completion-driven, and there is no background sweep. The
proposal's own premise — that a job generates occurrences on every device — describes software that
does not exist yet.

## Why the proposed index must not be added

`UNIQUE (parent_task_id, due_date)` looks harmless and is not. `parentTaskId` is **the subtask
hierarchy column**. It was added by `Migration9To10` ("adds parent_task_id column to the tasks
table") and is consumed by `core/tree/Cascade.kt` and `core/tree/TreeVisitor.kt`.

Two subtasks of the same parent sharing a due date is ordinary use. A parent task with "Draft the
proposal" and "Send the proposal" children, both due Friday, is not a data error. The index would
reject those inserts — turning a duplicate-prevention measure into a bug that blocks legitimate
work.

Two further problems, independent of the semantics:

- Room's auto-migration cannot express a **partial** index. The proposal's
  `WHERE parent_task_id IS NOT NULL AND due_date IS NOT NULL` has no representation in
  `Migration`, so the migration would be hand-written and then fail
  `scripts/check-room-schema-integrity.py` until an exported schema JSON matched it.
- If deterministic ids ever are wanted, `rec:` + parent + date produces ids that sort arbitrarily
  in a column whose ids have **no ordering guarantee** (UUID v4, confirmed 2026-10-08; see
  `2026-10-08-uuid-v4-not-v7.md`). The previous assumption of "lexicographically time-ordered"
  was incorrect — UUID v4 is random, not time-ordered.

## Decision

**No uniqueness constraint on `(parent_task_id, due_date)`. No deterministic recurrence ids.**

Roll-forward is already idempotent under the existing conflict policy, and the constraint would
reject legitimate sibling subtasks.

If a background recurrence sweep is ever built — generating occurrences for days the app was closed
— it re-opens this question, because a sweep *would* create rows on two devices at once. That feature
must ship together with a real answer, and that answer is a dedicated new column plus deterministic
ids, **not** `parent_task_id`. Until then, "app closed for three days → no occurrences generated"
remains true and is now a documented trade-off rather than an accident.

## Consequences

- The two-day blocking epic is dropped. Nothing downstream needed it.
- Recurrence across a closed app is still unimplemented. It is deliberately not implemented here.
- Any future sweep job must be reviewed against this ADR before it ships, because the duplicate
  scenario becomes real the moment occurrences stop being rolled in place.

## Links

- [2026-10-04-per-field-lww-conflict-policy](2026-10-04-per-field-lww-conflict-policy.md) — the
  policy that makes two updates to one row resolve
- `feature/tasks/domain/usecase/CompleteRecurringTaskUseCase.kt` — `rollForward`, and the one
  `TaskId.generate()`
- `feature/tasks/domain/model/RecurrenceSpec.kt` — `MAX_MISSED = 10`
- `core/tree/Cascade.kt` — why `parentTaskId` is the hierarchy, not recurrence