package com.singularity.todo.feature.tasks.domain.port

import com.singularity.todo.feature.tasks.domain.model.DependencyAnalysis
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * Validates task dependency operations.
 *
 * Cycle detection is intentionally minimal in v1 — only self-loop is checked.
 * Full BFS cycle detection (A→B→C→A) is deferred to v2.
 *
 * @see 2026-09-23-task-dependencies-completion
 */
interface DependencyValidator {

    /**
     * Asserts that adding [newDeps] to [taskId] does not create a cycle.
     *
     * Current implementation checks only self-loop ([taskId] ∈ [newDeps]).
     * Full cycle detection is tracked in issue tracker.
     *
     * @return [kotlin.Result] with [Unit] on success, or [IllegalArgumentException] on self-loop.
     */
    suspend fun assertNoCycles(taskId: TaskId, newDeps: Set<TaskId>): Result<Unit>

    /**
     * One traversal that returns both whether a cycle exists and the blocking edges.
     *
     * Consumer determines the appropriate scope to apply the result.
     *
     * @return [DependencyAnalysis] describing the cycle state of [taskId]'s dependency graph.
     */
    suspend fun analyzeDependencies(taskId: TaskId): DependencyAnalysis
}
