---
status: accepted
date: 2026-09-25
---

# CycleDetector Design

## Context

Lotti (Flutter) validates task dependencies for cycles before saving. Singularity Todo's `DependencyValidatorImpl` only checked self-loop (`A dependsOn A`). Transitive cycles (`A→B→C→A`) and cycles in subtasks, projects, and notes folders were undetected.

## Decision

### Generic `CycleDetector`

Located at `core/graph/CycleDetector.kt`. BFS-based, completely generic:

```kotlin
sealed class CycleError {
    data object SelfLoop : CycleError()
    data class Cycle(val path: List<String>, val edge: String) : CycleError()
    data class MissingNode(val nodeId: String) : CycleError()
}

fun <N> detectCycle(
    node: N,
    newParent: N,
    edges: (N) -> List<N>,
): Result<Unit>
```

BFS traverses **backward** from `newParent` to find if `node` is reachable. If `node` is found in the reachable set, a cycle exists.

### Usage in DependencyValidatorImpl

```kotlin
class DependencyValidatorImpl(
    private val taskDao: TaskDao,
    private val currentUser: ProfileAwareCurrentUser,
) {
    suspend fun validate(parentId: TaskId, childId: TaskId): Result<Unit> {
        val edges: (TaskId) -> List<TaskId> = { id ->
            taskDao.listAllDependenciesForUser(id, currentUser.scopedUserId.value)
                .map { TaskId.fromString(it) }
        }
        return detectCycle(childId, parentId, edges)
    }
}
```

`DependencyValidatorImpl` now:
- Takes `TaskDao` + `ProfileAwareCurrentUser` (no longer needs full `TaskRepository`)
- Uses `listAllDependenciesForUser` to load the dependency graph
- Calls `detectCycle` for full BFS cycle detection

### SelfLoop vs transitive cycle

`detectCycle` returns `SelfLoop` when `node == newParent`. Otherwise it runs BFS from `newParent` along incoming edges. If `node` is found during BFS, returns `Cycle(path, edge)`.

## Consequences

- `TreeVisitor` remains unchanged for other use cases (non-cycle-detection tree traversal).
- `DependencyValidatorImplTest` (13 cases) covers self-loop, linear chains, branching chains, branching with merges, deep chains, missing nodes.
- `FakeRepositories.InMemoryTaskDao.listAllDependenciesForUser` stub implemented for tests.

## Links

- `core/graph/CycleDetector.kt`
- `core/graph/CycleDetectorTest.kt`
- `DependencyValidatorImpl.kt`
