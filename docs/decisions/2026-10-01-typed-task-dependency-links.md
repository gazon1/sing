# Typed Task Dependency Links — verb column

## Context

The `task_dependencies` cross-ref table stores task-to-task dependency relationships
as an untyped `BLOCKS` graph. Product requires richer semantics: `FOLLOWS_UP`,
`DUPLICATES`, `FIXES`, `SUPERSEDES`. This enables better UX (semantic chip labels,
smarter sorting) and lays the foundation for cycle-detection UI.

## Decision

Add a `verb TEXT DEFAULT 'BLOCKS'` column to `task_dependencies` and model it in
the domain layer as `DependencyVerb` enum + `TaskDependency` data class.

## Schema

```sql
ALTER TABLE task_dependencies ADD COLUMN verb TEXT DEFAULT 'BLOCKS' NOT NULL;
-- No composite index added speculatively; add (to_task_id, verb) only when a
-- query proves the need (per BAN list).
```

## Domain

```kotlin
enum class DependencyVerb {
    BLOCKS,      -- default, existing behaviour
    FOLLOWS_UP,  -- this task follows up on the other
    DUPLICATES,  -- this task duplicates the other
    FIXES,       -- this task fixes the other
    SUPERSEDES,  -- this task supersedes the other
}

data class TaskDependency(
    val ownerTaskId: TaskId,       -- task that "owns" the dependency (has the edge)
    val dependencyTaskId: TaskId,  -- target of the edge
    val verb: DependencyVerb,
)

interface DependencyValidator {
    // existing: self-loop check
    suspend fun assertNoCycles(taskId: TaskId, newDeps: Set<TaskId>): Result<Unit>
    // v2: one traversal → both pieces of information
    suspend fun analyzeDependencies(taskId: TaskId): DependencyAnalysis
}

data class DependencyAnalysis(
    val containsCycle: Boolean,
    val blockers: List<TaskDependency>,  -- edges participating in the cycle
)
```

## Repository

```kotlin
interface TaskRepository {
    // Primary typed API — one edge at a time
    suspend fun setDependency(
        from: TaskId,
        to: TaskId,
        verb: DependencyVerb,
        enabled: Boolean,
    ): Result<Unit>

    // Existing API — rewritten as default-BLOCKS wrapper
    suspend fun setDependencies(taskId: TaskId, deps: Set<TaskId>): Result<Unit> =
        deps.forEach { setDependency(taskId, it, BLOCKS, true) }

    // New typed observer
    fun observeTypedDependencies(taskId: TaskId): Flow<List<TaskDependency>>

    // Existing — kept for backward compat, returns Set<TaskId> (verb dropped)
    fun observeDependencies(taskId: TaskId): Flow<Set<TaskId>>
}
```

## Why not separate tables

One row per `(owner, dependency, verb)` in the existing cross-ref is the right
granularity. A separate `task_dependency_verbs` table would require JOINs for every
read and complicate the unique constraint. The existing composite PK
`(task_id, depends_on_task_id)` is extended to `(task_id, depends_on_task_id, verb)`.
An `enabled` flag (per the plan) is deferred — the toggle use case requires UX
validation before API design.

## Consequences

- Existing `setDependencies` + `observeDependencies` continue to work unchanged
  (default verb = BLOCKS).
- `DependencyValidator.assertNoCycles` stays self-loop-only in v1;
  `analyzeDependencies` is added alongside it for future cycle-detection UI.
- No speculative index on `verb` — added only when a real query pattern demands it.
- `Migration24To25` is a marker (Room auto-generates the ALTER).

## Sync serialization

`DependencyVerb` is serialized as the enum entry name (`"BLOCKS"`, `"FOLLOWS_UP"`, …)
in JSON. `DependencyVerb.valueOf` with fallback to `BLOCKS` is used on deserialization.

**`TaskDependencyCrossRef` does not implement `SyncableEntity`.** Cross-ref sync is
deferred because `task_dependencies` has no `user_id` column — the ownership model
(whose HLC clocks the edge?) requires additional design. The serialization contract
is documented here so that the future sync implementation is consistent.

## Foreign-key constraints

`task_dependencies` references `tasks(id)` via `task_id` and `depends_on_task_id` but
declares **no FK constraints** at the database level. Referential integrity is enforced
by the repository layer. Rationale:

- The cross-ref table has **no `user_id`** — user isolation is done via SQL subqueries
  against `tasks`. A proper FK would need `(task_id, user_id)` compound, requiring a
  separate schema change.
- `ON DELETE` semantics are handled by `clearDependenciesForUser` in the repository,
  which is called when a task is deleted.
- Adding FK constraints now would be a breaking schema migration with no consumer
  driving the need.

Deferred: add `user_id` to `task_dependencies` to enable compound FK and unblock sync.

## Links

- `Migration24To25.kt`
- `DependencyVerb.kt`
- `TaskDependency.kt`
- `TaskRepository.setDependency`
- `DependencyValidator.analyzeDependencies`
