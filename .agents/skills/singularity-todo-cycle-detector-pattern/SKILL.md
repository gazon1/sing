---
name: singularity-todo-cycle-detector-pattern
description: Universal BFS cycle detector for graph-like domain relations in Singularity Todo. Use when adding dependency/parent validation to tasks, subtasks, projects, or notes-folders. Covers the CycleDetector API, CycleError sealed hierarchy, and the 4 integration points.
---

# Cycle Detector Pattern

Cycle detection validates that adding a dependency or setting a parent does not create a cycle in the graph. This project uses a **universal BFS cycle detector** in `core/graph/CycleDetector.kt` that works for any node type and any edge relation.

## The Problem

When tasks have `dependsOn: Set<TaskId>`, setting `taskB.dependsOn = {taskA}` is valid, but setting `taskA.dependsOn = {taskB}` creates a cycle (A→B→A). The same applies to:
- Subtasks: parent ↔ child
- Projects: parent ↔ child project
- Notes folders: parent ↔ child folder

## The API

```kotlin
// core/graph/CycleDetector.kt
sealed interface CycleError {
    data object SelfLoop : CycleError
    data class PathBased(val path: List<String>) : CycleError
}

class CycleDetector {
    /**
     * Checks whether adding [newParent] as a successor/predecessor of [node]
     * would create a cycle in the graph described by [getSuccessors].
     *
     * @param node The node being modified.
     * @param newParent The target being added as a successor/predecessor of [node].
     * @param getSuccessors Returns the current successors of any node (the graph edge function).
     * @return [Result.success] if the edge is cycle-free, [Result.failure] with [CycleError] otherwise.
     */
    fun <N> check(node: N, newParent: N, getSuccessors: (N) -> List<N>>: Result<Unit, CycleError>
}
```

**Key properties:**
- Returns `Result<Unit, CycleError>` — caller decides how to handle the error
- `SelfLoop` — node points to itself (`A → A`)
- `PathBased(path)` — a path of length ≥2 exists, described by the path of node IDs
- BFS starting from `newParent` — if we reach `node`, a cycle would form

## Usage Pattern

```kotlin
val cycleDetector = CycleDetector()

// In a use case or repository
fun addDependency(taskId: TaskId, dependsOnId: TaskId): Result<TaskId, CycleError> {
    return cycleDetector.check(taskId, dependsOnId) { id ->
        taskRepo.get(id)?.dependsOn?.toList() ?: emptyList()
    }.map {
        taskRepo.addDependency(taskId, dependsOnId)
    }
}
```

## The 4 Integration Points

### 1. Task Dependencies (`dependsOn: Set<TaskId>`)

```kotlin
// TaskRepository / AddTaskDependencyUseCase
class AddTaskDependencyUseCase(
    private val taskRepo: TaskRepository,
    private val cycleDetector: CycleDetector,
) {
    suspend operator fun invoke(taskId: TaskId, dependsOnId: TaskId): Result<TaskId, CycleError> {
        return cycleDetector.check(taskId, dependsOnId) { id ->
            taskRepo.get(id)?.dependsOn?.toList() ?: emptyList()
        }.map {
            taskRepo.addDependency(taskId, dependsOnId)
            taskId
        }
    }
}
```

### 2. Subtasks (parent ↔ children)

```kotlin
// SubtaskRepository / SetSubtaskParentUseCase
class SetSubtaskParentUseCase(
    private val subtaskRepo: SubtaskRepository,
    private val cycleDetector: CycleDetector,
) {
    suspend operator fun invoke(subtaskId: SubtaskId, newParentId: SubtaskId): Result<SubtaskId, CycleError> {
        return cycleDetector.check(subtaskId, newParentId) { id ->
            subtaskRepo.getChildren(id)
        }.map {
            subtaskRepo.setParent(subtaskId, newParentId)
            subtaskId
        }
    }
}
```

### 3. Projects (parent ↔ children)

```kotlin
// ProjectRepository / SetProjectParentUseCase
class SetProjectParentUseCase(
    private val projectRepo: ProjectRepository,
    private val cycleDetector: CycleDetector,
) {
    suspend operator fun invoke(projectId: ProjectId, parentId: ProjectId?): Result<ProjectId, CycleError> {
        if (parentId == null) return Result.success(projectId) // root
        return cycleDetector.check(projectId, parentId) { id ->
            projectRepo.get(id)?.childIds ?: emptyList()
        }.map {
            projectRepo.setParent(projectId, parentId)
            projectId
        }
    }
}
```

### 4. Notes Folders (parent ↔ children)

```kotlin
// FolderRepository / SetFolderParentUseCase
class SetFolderParentUseCase(
    private val folderRepo: FolderRepository,
    private val cycleDetector: CycleDetector,
) {
    suspend operator fun invoke(folderId: FolderId, parentId: FolderId?): Result<FolderId, CycleError> {
        if (parentId == null) return Result.success(folderId)
        return cycleDetector.check(folderId, parentId) { id ->
            folderRepo.get(id)?.childIds ?: emptyList()
        }.map {
            folderRepo.setParent(folderId, parentId)
            folderId
        }
    }
}
```

## CycleError Handling in the UI

```kotlin
// In a ViewModel intent handler
is TaskDetailIntent.SetDependency -> {
    val result = addDependencyUseCase(taskId, intent.dependsOnId)
    result.fold(
        onSuccess = { /* dependency added */ },
        onFailure = { error ->
            when (error) {
                is CycleError.SelfLoop ->
                    _events.trySend(TaskDetailUiEvent.Error("A task cannot depend on itself"))
                is CycleError.PathBased ->
                    _events.trySend(TaskDetailUiEvent.Error("Cycle detected: ${error.path.joinToString(" → ")}"))
            }
        },
    )
}
```

## How BFS Works

```
check(A, B):
  if B == A → SelfLoop
  BFS from B:
    visit B → C → D
    if we reach A → PathBased([B, C, D, A])
    else → no cycle
```

```kotlin
fun <N> check(node: N, newParent: N, getSuccessors: (N) -> List<N>): Result<Unit, CycleError> {
    if (node == newParent) return Result.failure(CycleError.SelfLoop)

    val visited = mutableSetOf<N>()
    val queue = ArrayDeque<N>()
    queue.add(newParent)

    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (current == node) {
            return Result.failure(CycleError.PathBased(buildPath(node, newParent, getSuccessors)))
        }
        if (visited.add(current).not()) continue
        queue.addAll(getSuccessors(current))
    }
    return Result.success(Unit)
}
```

## Adding a New Edge Type

When adding cycle detection to a new domain relation:

1. Create `SetXxxParentUseCase` that takes `CycleDetector` as a constructor dep
2. Use `cycleDetector.check(node, newParent) { getSuccessors(it) }`
3. Map `Result.success` to the actual operation
4. Handle `CycleError.SelfLoop` and `CycleError.PathBased` in the ViewModel
5. Expose the error to the UI as a `UiEvent.Error` message
6. Add unit test with `FakeTaskRepository` + `CycleDetector`

## Testing

```kotlin
class CycleDetectorTest {
    private val detector = CycleDetector()

    @Test
    fun `self-loop returns SelfLoop`() {
        val result = detector.check("A", "A") { emptyList() }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is CycleError.SelfLoop)
    }

    @Test
    fun `direct cycle returns PathBased`() {
        // A → B → A
        val successors = mapOf("A" to listOf("B"), "B" to listOf("A"))
        val result = detector.check("A", "B") { successors[it] ?: emptyList() }
        assertTrue(result.isFailure)
        val pathError = result.exceptionOrNull() as CycleError.PathBased
        assertTrue("A" in pathError.path && "B" in pathError.path)
    }

    @Test
    fun `no cycle returns success`() {
        // A → B → C (no cycle)
        val successors = mapOf("A" to listOf("B"), "B" to listOf("C"))
        val result = detector.check("A", "C") { successors[it] ?: emptyList() }
        assertTrue(result.isSuccess)
    }
}
```

## DI Registration

```kotlin
// domainModule() in Modules.kt
single { CycleDetector() }
```

No state — stateless utility. Registered as `single` (one instance for the whole app).

## See Also

- `singularity-todo-feature-scaffold` — canonical CRUD feature template with use case pattern
- `singularity-todo-test-helpers` — `FakeRepositories` for testing use cases
- `docs/decisions/2026-09-25-cycle-detector-design.md` — ADR for the cycle detector design
