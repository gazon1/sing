---
title: "Recurrence end date lives inside the RecurrenceSpec blob, not in new columns"
date: 2026-10-05
tags: [tasks, recurrence, serialization, sync]
status: accepted
---

## Context

A recurring task had no way to stop. "Every Monday" generated occurrences forever, and the user could not say "until December 31" or "10 times". The obvious implementation is three new columns on `tasks`: `recurrence_end_date`, `recurrence_max_occurrences`, `recurrence_occurrence_count`, a `Migration37To38`, a version bump, and a backup DTO change.

Two facts about this codebase make that plan worse than it looks.

**Recurrence is not a set of columns.** `TaskEntity` carries the whole rule in one `recurrence_rule TEXT` (`Entities.kt:53`), and `Mappers.kt:92` does `StableJson.decodeFromString<RecurrenceSpec>(it)`. A constraint carried inside `RecurrenceSpec` is therefore persisted, synced, backed up and restored with no schema change at all.

**The sync layer has no per-field conflict policy.** `SyncPatchBuilder` diffs a whole row and the server resolves last-writer-wins; there is no `ConflictResolver` and `StableJson` defines no merge rule. A counter that must only ever increase cannot be merged safely under those semantics — two devices completing the same series would each keep their own count, and max-wins would have to be *specified* before it could be implemented.

## Idea

Two options were weighed:

1. Add the three columns, bump the schema, and introduce a monotonic-counter conflict rule.
2. Add an end date inside `RecurrenceSpec`, and defer "after N times" entirely.

## Decision

The end date is a constructor property on each `RecurrenceSpec` variant, via a `RecurrenceTermination` wrapper. No schema change, no migration, no version bump. "After N occurrences" is deferred.

```kotlin
@Serializable
sealed class RecurrenceSpec {
    abstract val base: RecurrenceBase
    abstract val termination: RecurrenceTermination?   // null = unbounded

    // …Interval / Weekly / Monthly / Yearly, each with
    //   `override val termination: RecurrenceTermination? = null`
}

@Serializable
data class RecurrenceTermination(val endDate: LocalDate? = null)
```

**It is a constructor property, not a `var` on the sealed base.** A first draft proposed `var termination`. That was rejected on review: `data class` `equals`, `hashCode`, `copy` and `toString` are generated from constructor properties only, so a base-class `var` would be invisible to all four. `spec.copy(amount = 2)` would silently drop the end date, and two rules differing only by end date would compare equal — in a value that Room stores and the sync diff compares. The four constructor additions cost more churn and remove the whole class of bug.

**Termination is `softDelete`, not a new archive call.** `TaskRepository` has no `archive()`; the app's own Archive action in `TaskLifecycleSlot` is a `softDelete`, and `Task.isTrashed` is `archivedAt != null`. Reusing it means a finished series is restorable from the trash exactly like a hand-archived task, instead of a second lifecycle that nothing else knows about.

**The boundary is inclusive** — `nextDue > endDate` stops the series, so an occurrence landing exactly on the end date still runs. "Repeat until Dec 31" means Dec 31 is included.

**In `CATCH_UP`, the guard sits after the historical copies are created.** Those copies represent occurrences that already happened and are correct; only the roll-forward is suppressed. A guard before the copy loop would discard them.

## Rationale

An end date needs no new synced field, so the sync protocol is untouched — it rides the existing `recurrence_rule` diff. The occurrence counter *is* a new synced field with monotonic semantics, which the current whole-row LWW merge cannot express. Cutting the counter turns a protocol change into a one-file domain change.

Backward compatibility is structural: rules written before this field simply omit `termination`, and `StableJson` sets `ignoreUnknownKeys = true`.

## Consequences

- Adding `termination` is one defaulted constructor parameter per variant. Every existing construction site keeps compiling — `RecurrencePickerSheet`, `RecurrenceParser`, test fixtures — because the default is `null`.
- The compiler will not catch a construction site that means to set a termination and forgets. `CompleteRecurringTaskUseCaseTest` asserts copy/equality preservation and a legacy-blob decode specifically to cover that gap.
- "Repeat N times" needs a max-wins field rule specified in the sync protocol before it can ship. That is a protocol change, not a field addition, and it needs a product answer on whether devices may silently diverge.
- A series with an end date now soft-deletes itself. That is a visible behaviour change and belongs in release notes. Series without an end date are untouched.

## Links

- `feature/tasks/domain/model/RecurrenceSpec.kt` — `RecurrenceTermination`, `termination`, `isExhaustedBy`
- `feature/tasks/domain/usecase/CompleteRecurringTaskUseCase.kt` — the guard, four call sites
- `commonTest/.../CompleteRecurringTaskUseCaseTest.kt`
- `2026-09-18-task-dependencies` — for the dependency semantics used elsewhere in this area