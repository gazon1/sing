---
title: "Task Dependencies (MR-1): schema, domain, UI badge, MCP tool"
date: 2026-09-18
tags: [tasks, schema, ui, mcp, dependencies]
status: accepted
---

## Context

Task dependencies let users express "task B cannot start until task A is done" — the blocking semantics from Taskwarrior. This is the first of three sequential MRs:

1. **MR-1 (this ADR)**: `Task.dependsOn: Set<TaskId>`, join-table, computed `isBlocked`, UI badge, MCP tool `task.set_dependencies`. No reverse direction (`isBlocking`), no cycle detection.
2. **MR-2**: Recurring tasks (3 modes).
3. **MR-3**: Tag groups + tag inheritance.

## Decision

### Schema

**Join table** `task_dependencies` (no `ON DELETE CASCADE` on `task_id` — a dependent task is not deleted when its dependency is deleted):

```sql
CREATE TABLE task_dependencies (
    task_id TEXT NOT NULL,
    depends_on_task_id TEXT NOT NULL,
    PRIMARY KEY (task_id, depends_on_task_id)
);
CREATE INDEX idx_task_deps_task ON task_dependencies(task_id);
CREATE INDEX idx_task_deps_dep ON task_dependencies(depends_on_task_id);
```

- `TaskEntity` has **no** `dependsOn` column — it is computed at query time via `TaskDao.getDependencyIdsForTask`.
- `Task.dependsOn: Set<TaskId>` lives only in the domain model (loaded via repository).
- `TaskDependencyCrossRef` is the Room entity mirroring the join table.

### Domain model

`Task.dependsOn: Set<TaskId>` is stored only in the join table, never as a denormalized column on `TaskEntity`.

`Computed.isBlocked(task, allTasks)` (pure, in `Computed.kt`):

```
isBlocked = dependsOn.isNotEmpty() && dependsOn.any { depId →
    val dep = allTasks.find { it.id == depId }
    dep != null && !dep.isCompleted && !dep.isTrashed
}
```

- `isBlocked` is **never stored** — always derived at render time from the full task list.
- `isTrashed` dependencies are ignored (a trashed task is no longer a blocker).

### Repository

Three new methods on `TaskRepository`:

```kotlin
fun watchDependencies(taskId: TaskId): Flow<Set<TaskId>>
fun watchBlockingBy(taskId: TaskId): Flow<Set<TaskId>>
suspend fun setDependencies(taskId: TaskId, deps: Set<TaskId>): Result<Unit>
```

`setDependencies` uses a diff-and-apply pattern: reads current deps, computes added/removed, applies upsert + delete per edge.

### UI

- `TaskUi` gains `dependsOn: Set<TaskId>` and `isBlocked: Boolean` fields.
- `TaskMetaRow` gains a `Block` icon (🛇, `Icons.Default.Block`) shown when `isBlocked = true && !isCompleted`.
- `AgendaEvaluator` passes `isBlocked` to each `AgendaRowItem`.
- `AgendaBadge.Blocked` added to the enum (not rendered as a section badge in v1; reserved for future use).
- `TaskDetailIntent.Domain.SetDependencies` intent added; `TaskDetailViewModel` calls `taskRepo.setDependencies`.
- `TaskMenuBuilder` gains "Set dependencies" menu item wired to `onSetDependencies: ((Set<TaskId>) -> Unit)?`.
- `TaskMenuActions` gains `onSetDependencies` callback.
- `TaskList.buildFlatTaskList` computes `isBlocked` per task via `TaskComputed.isBlocked(task, tasks)`.

### MCP tool

`task.set_dependencies` (idempotent, `ToolAnnotations(idempotentHint = true)`):

- Input: `{ taskId: string, dependsOnTaskIds: string[] }`
- Validation: task exists, dep IDs valid, no self-dependency, all dep tasks exist.
- Output: `{ taskId, dependsOnTaskIds, success: boolean, error?: string }`

### Backup

`BackupPayload` gains `taskDependencies: List<TaskDependencyDto>`.
`BackupExporter` writes `emptyList()` (placeholder — full implementation is a follow-up).
`BackupImporter` restores `taskDependencies` via `taskDao.upsertDependency`.

## Consequences

- A task may have zero, one, or many dependencies.
- `dependsOn` is **not** enforced at the data layer — completion is always allowed. UI consumers (`TaskList`, `AgendaEvaluator`) display `isBlocked` to inform users.
- `isBlocking` (reverse direction) is not in MR-1 — a separate follow-up can add `watchBlockingBy` to `TaskUi` if needed.
- Cycle detection is deferred — cycles are rare and the cost of a DFS on every `setDependencies` call is non-trivial for large task graphs.
- Self-dependency is validated in the MCP tool and silently ignored by the join-table upsert (PRIMARY KEY prevents the duplicate).

## Links

- MR-1 implementation: `TaskDependencyCrossRef`, `TaskDao`, `Computed.isBlocked`, `TaskDetailIntent.SetDependencies`, `SetDependenciesTool`
- `core/tree/Cascade.kt::cascadeUp` — the inheritance pattern used by MR-3 tag groups, applied here conceptually for `effectiveTags`
