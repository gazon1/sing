---
title: "OwnerCountsCalculator: single-pass pattern for computing reassign and erase counts"
date: 2026-10-19
tags: [auth, database, performance]
status: accepted
---

## Context

Before an offline user attaches a real account, the attach screen must show two previews simultaneously:

- "N tasks, M notes will be **moved** to your account"
- "N tasks, M notes will be **erased** from this device"

The two numbers are different: erasing includes child rows (tag references, checklist items, dependencies, inherited tag groups) that reassigning does not. Showing stale or loading numbers after the user has already chosen is worse than showing accurate ones that were pre-computed.

The naive approach — two separate queries, one returning `ReassignCounts` and one returning `EraseCounts` — requires two passes over the same base tables and two identical sets of parent-id reads. This is wasteful and, more importantly, the counts would be computed at two different points in time, making them potentially inconsistent.

## Decision

`OwnerCountsCalculator` computes both `ReassignCounts` and `EraseCounts` in a **single pass** over the database, returning a `CountsPair(reassign, erase)` from one `compute(resolved: OwnerRowIds)` call.

```kotlin
class OwnerCountsCalculator(private val database: AppDatabase) {

    data class CountsPair(
        val reassign: ReassignCounts,
        val erase: EraseCounts,
    )

    suspend fun compute(resolved: OwnerRowIds): CountsPair {
        // One set of parent-id reads shared by both interpretations
        val taskIds = scope.listTaskIdsForOwner(scopes)
        val projectIds = scope.listProjectIdsForOwner(scopes)

        // Base table counts — identical in both reassign and erase
        val tasks = reassignDao.countTasks(scopes)
        val notes = reassignDao.countNotes(scopes)
        // ... same 10 base-table counters

        // Reassign-specific: no child-row expansion needed
        val reassignCounts = ReassignCounts(tasks = tasks, notes = notes, ...)

        // Erase-specific: child rows require taskIds / projectIds
        val tagRefs = eraseDao.countTagRefs(taskIds, scopes)
        val dependencies = eraseDao.countDependencies(taskIds, scopes)
        val checklists = eraseDao.countChecklistItems(taskIds, scopes)
        val inherited = eraseDao.countInheritedTagGroups(projectIds, scopes)

        val eraseCounts = EraseCounts(
            tasks = tasks,    // shared base count
            notes = notes,
            tagRefs = tagRefs,   // erase-only
            dependencies = dependencies,  // erase-only
            checklists = checklists,       // erase-only
            inheritedTagGroups = inherited, // erase-only
            ...
        )

        return CountsPair(reassignCounts, eraseCounts)
    }
}
```

## Rationale

**Why one pass is better than two:**

1. **Consistency**: both counts are read from the same database state at the same moment. A write between the two passes would make them inconsistent in a way the UI cannot detect.

2. **Performance**: one set of parent-id reads (`taskIds`, `projectIds`) instead of two. The base-table counts are identical in both interpretations and are computed once.

3. **Simplicity for callers**: `LocalAccountClaimer.summarise()` calls `calculator.compute(resolved)` once and destructures the `CountsPair`. The screen gets both numbers from one call, one coroutine suspension, one transaction.

**Why not put this on `OwnerScopedReassigner` or `OwnerScopedEraser`?**

- Both `OwnerScopedReassigner.summarise()` and `OwnerScopedEraser.preview()` existed and each computed only one of the two counts.
- The reassigner's `summarise()` is dead code after `OwnerCountsCalculator` was introduced — it is never called.
- Eraser.preview() is used only by the eraser itself.
- The calculator is the right home because it is the only object whose single responsibility is "produce both counts for a screen that has not yet chosen".

## Consequences

- `OwnerScopedReassigner.summarise()` is removed as dead code. `LocalAccountClaimer` uses `OwnerCountsCalculator` exclusively.
- `OwnerScopedEraser.preview()` is unchanged and remains used internally for the eraser flow.
- `LocalAccountClaimer.summarise()` is a thin wrapper around `calculator.compute()` — it constructs the `LocalDataDisposition.Keep` with the right summary shape. This can be inlined if the callers do not need the indirection.
- Adding a third disposition variant (e.g. "move to a different existing account") requires extending `CountsPair` or returning a map — the current structure assumes exactly two interpretations.
- The calculator is a `single` in Koin DI because it is stateless and every screen that needs it can share the same instance.
