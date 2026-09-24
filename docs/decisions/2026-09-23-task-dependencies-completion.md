---
status: accepted
date: 2026-09-23
authors: Singularity Developer
---

# Task Dependencies (Blocked/Blocking)

## Context

Taskwarrior-style dependencies (`dependsOn: Set<TaskId>`) were added to `Task` domain model but were never populated when loading tasks from the repository. `TaskEntity.toTask()` always returned `dependsOn = emptySet()`, and `TaskDao` had no query to efficiently load cross-references. The `isBlocked` computed property on `Task` therefore always returned `false`.

Additionally, `setDependencies()` in `TaskRepositoryImpl` accepted any set of dependency IDs without validating self-loops (A depends on A) or cycles (A→B→C→A).

## Decision

### 1. `TaskExtras` batch loader

Introduce a `userTasksWithExtras(uid, source)` helper in `TaskRepositoryImpl` that combines the entity `Flow<List<TaskEntity>>` with two cross-ref flows via `combine()`:

```kotlin
// TaskRepositoryImpl
private data class TaskExtras(
    val tagsByTask: Map<String, List<String>>,
    val depsByTask: Map<String, Set<String>>,
)

private fun userTasksWithExtras(
    uid: UserId,
    source: Flow<List<TaskEntity>>,
): Flow<List<Task>> {
    val tagsFlow = taskDao.observeTagCrossRefs(uid.value).map { rows ->
        rows.groupBy { it.taskId }.mapValues { (_, rows) -> rows.map { it.tagId } }
    }
    val depsFlow = taskDao.observeDependencyCrossRefs(uid.value).map { rows ->
        rows.groupBy { it.taskId }.mapValues { (_, rows) -> rows.map { it.dependsOnTaskId }.toSet() }
    }
    val extrasFlow = combine(tagsFlow, depsFlow) { tags, deps ->
        TaskExtras(tagsByTask = tags, depsByTask = deps)
    }
    return combine(source, extrasFlow) { rows, extras ->
        rows.map { e ->
            e.toTask(
                tags = extras.tagsByTask[e.id].orEmpty().map { TagId.fromString(it) },
                dependsOn = extras.depsByTask[e.id].orEmpty().map { TaskId.fromString(it) }.toSet(),
            )
        }
    }
}
```

`observeTagCrossRefs` and `observeDependencyCrossRefs` are new non-suspend DAO methods returning `Flow<List<...>>`. This avoids the N+1 problem of calling `listAllTagsForUser()` (suspend) inside a `Flow.map {}` lambda.

### 2. `toTask(tags, dependsOn)` mapper signature

`TaskEntity.toTask()` is extended with two optional parameters so that `TaskRepositoryImpl` can pass the pre-loaded extras:

```kotlin
internal fun TaskEntity.toTask(
    tags: List<TagId> = emptyList(),
    dependsOn: Set<TaskId> = emptySet(),
): Task
```

### 3. `DependencyValidator` port + impl

Minimal v1 validator checks only self-loop (task depending on itself). Full BFS cycle detection (A→B→C→A) is deferred to v2.

```kotlin
interface DependencyValidator {
    suspend fun assertNoCycles(taskId: TaskId, newDeps: Set<TaskId>): Result<Unit>
}

class DependencyValidatorImpl(private val taskDao: TaskDao) : DependencyValidator {
    override suspend fun assertNoCycles(taskId: TaskId, newDeps: Set<TaskId>): Result<Unit> {
        if (taskId.value in newDeps.map { it.value }) {
            return Result.failure(IllegalArgumentException("Task cannot depend on itself: ${taskId.value}"))
        }
        return Result.success(Unit)
    }
}
```

`TaskRepositoryImpl.setDependencies()` calls `dependencyValidator.assertNoCycles(taskId, deps).getOrThrow()` before writing to the database.

> **Note**: `require()` inside `runCatchingResult {}` wraps the `IllegalArgumentException` in `AppError.Unknown`, making it impossible for callers to inspect the original error message. The explicit `if/return Result.failure()` pattern avoids this wrapping.

### 4. `TaskDao` new methods

```kotlin
@Query("SELECT * FROM task_tags WHERE task_id IN (SELECT id FROM tasks WHERE user_id = :userId)")
fun observeTagCrossRefs(userId: String): Flow<List<TaskTagCrossRef>>

@Query("SELECT * FROM task_dependencies WHERE task_id IN (SELECT id FROM tasks WHERE user_id = :userId)")
fun observeDependencyCrossRefs(userId: String): Flow<List<TaskDependencyCrossRef>>
```

Both return `Flow<List<...>>` (non-suspend) to enable use inside `userTasksWithExtras()`.

### 5. `isBlocked` computed property

`Task.isBlocked` checks whether any dependency in `dependsOn` is not completed:

```kotlin
val isBlocked: Boolean
    get() = dependsOn.isNotEmpty() && dependsOn.any { dep ->
        computed[dep]?.completedAt == null
    }
```

Requires `TaskComputed` to be in scope (already provided by `TaskRepositoryImpl.observeAll/observeByFilter`).

## Consequences

- `Task.tags` and `Task.dependsOn` are now correctly populated in all list views (`observeAll`, `observeByFilter`, `observeByDate`, `observeSubtasks`).
- `isBlocked` badge will appear on task cards when dependencies are unfinished.
- Self-loop dependency is rejected at `setDependencies()` call site; cycle detection (A→B→C→A) is deferred.
- Room schema unchanged (tables `task_tags` and `task_dependencies` already existed).
- No migration needed for this fix.

## Alternatives considered

**Alternative A: `listAllTagsForUser()` + `listAllDependenciesForUser()` (suspend) inside `Flow.map {}`**

Not possible: Room suspend functions cannot be called from non-suspend lambdas. The Flow lambdas in `combine()` are non-suspend.

**Alternative B: Single JOIN query `observeTagsAndDeps(userId)` returning a combined result**

Room doesn't support `GROUP BY` or `group_concat`, so a single JOIN would return N×M rows (cartesian product). Two separate queries with Kotlin-side `groupBy` is the correct approach.

**Alternative C: Eager load in `observeAll()` via JOIN**

Would couple entity loading to cross-ref loading unconditionally. The `userTasksWithExtras()` helper is explicit and composable.
