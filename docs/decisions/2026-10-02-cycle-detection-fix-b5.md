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

The BFS is factored into a private helper that walks the ordering graph from `startId` and stops as soon as `targetId` is dequeued.

**Cost, stated correctly.** The early exit fires only when the target *is* reached — that is, when the edge really would close a cycle. When the target is unreachable the walk cannot know that until it has exhausted everything reachable from `startId`, so a single check is `O(V + E)` over the reachable part of the graph, and the refusal case is the cheap one. A caller that checks several independent candidates pays one such walk per candidate; the walks are not shared.

(Since 2026-11-15 the helper is `isReachable(startId, targetId, uid): Boolean` — it reports reachability rather than returning the reachable set, because reachability is all the caller ever read.)

```kotlin
override suspend fun assertNoCycles(taskId: TaskId, newDeps: Set<TaskId>): Result<Unit> {
    if (taskId in newDeps) {
        return Result.failure(IllegalArgumentException("Task cannot depend on itself: ${taskId.value}"))
    }
    val uid = currentUser.scopedUserId.value
    for (dep in newDeps) {
        if (isReachable(dep.value, taskId.value, uid.value)) {
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
- BFS is called only for new edges (not all edges), so the cost is one walk per new edge — `O(new_deps × (V + E))` in the worst case
- The early exit bounds the case where a cycle exists, not the case where none does: a clean edge is proven clean only by exhausting what is reachable from it
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
