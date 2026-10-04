---
title: "Fix B5: assertNoCycles uses full BFS, not just self-loop check"
date: 2026-10-02
status: accepted
tags: [data-integrity, dependency-validation, bugfix]
---

## Context

`DependencyValidatorImpl.assertNoCycles` was checking only for self-loop (task depending on itself):
```kotlin
if (taskId.value in newDeps.map { it.value }) {
    return Result.failure(IllegalArgumentException("Task cannot depend on itself: ${taskId.value}"))
}
return Result.success(Unit)
```

But `TaskRepositoryImpl.setDependencies` calls `assertNoCycles` before persisting dependency edges. Multi-node cycles (A→B→C→A) would be **persisted to the database** without any error, because `assertNoCycles` returned `success`.

`analyzeDependencies` already performed a full BFS and correctly detected cycles. The bug was that `assertNoCycles` — the guard called at write time — did not use this BFS logic.

## Decision

`assertNoCycles` now performs a BFS from each new dependency to check if `taskId` is reachable. If any new dep can reach `taskId`, the edge is rejected as a cycle.

The BFS is factored into a private `bfsReachableFrom(startId, targetId, uid)` method that returns early when `targetId` is found — the common case (no cycle) is O(1) for the first new dep.

```kotlin
override suspend fun assertNoCycles(taskId: TaskId, newDeps: Set<TaskId>): Result<Unit> {
    if (taskId.value in newDeps.map { it.value }) {
        return Result.failure(IllegalArgumentException("Task cannot depend on itself: ${taskId.value}"))
    }
    val uid = currentUser.scopedUserId.value
    for (dep in newDeps) {
        val reachable = bfsReachableFrom(dep.value, taskId.value, uid.value)
        if (taskId.value in reachable) {
            return Result.failure(IllegalArgumentException(
                "Adding dependency ${taskId.value} → ${dep.value} would create a cycle",
            ))
        }
    }
    return Result.success(Unit)
}
```

## Rationale

- `analyzeDependencies` already had the BFS — reuse the same mental model
- BFS is called only for new edges (not all edges), so performance is O(new_deps × visited)
- Early exit on `targetId` found means the common case (no cycle) exits after exploring a small fraction of the graph
- `assertNoCycles` is the **write-time guard**; it MUST catch cycles before they persist

## Consequences

- Multi-node cycles (A→B→C→A) are now rejected at write time, not just at UI-time
- `bfsReachableFrom` is private and is NOT a public API change
- `DependencyValidatorImpl.analyzeDependencies` unchanged — continues to support the cycle-detection UI (Phase 7b)
- Test coverage for `assertNoCycles` multi-node case should be added to `DependencyValidatorTest`

## Links

- B5 in `docs/decisions/DIGEST.md` — Phase 0 finding
- Phase 7b: cycle detection UI (DependencyPickerSheet marks cycle edges)
- `DependencyValidatorImpl.kt`
- `TaskRepositoryImpl.kt:346` — call site
