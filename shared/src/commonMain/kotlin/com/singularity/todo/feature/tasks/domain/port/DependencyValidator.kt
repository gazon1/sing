package com.singularity.todo.feature.tasks.domain.port

import com.singularity.todo.feature.tasks.domain.model.DependencyAnalysis
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * Validates task dependency operations.
 *
 * [assertNoCycles] walks the ordering graph from each new dependency, so both a
 * self-loop and a multi-node cycle are refused.
 */
interface DependencyValidator {
    /**
     * Asserts that adding [newDeps] to [taskId] does not create a cycle.
     *
     * Checks the self-loop ([taskId] ∈ [newDeps]) and walks the ordering graph
     * from each new dependency to see whether [taskId] is reachable.
     *
     * @return [kotlin.Result] with [Unit] on success, or [IllegalArgumentException]
     *   when the edge would close a cycle.
     */
    suspend fun assertNoCycles(
        taskId: TaskId,
        newDeps: Set<TaskId>,
    ): Result<Unit>

    /**
     * One traversal that returns both whether a cycle exists and the blocking edges.
     *
     * Consumer determines the appropriate scope to apply the result.
     *
     * @return [DependencyAnalysis] describing the cycle state of [taskId]'s dependency graph.
     */
    suspend fun analyzeDependencies(taskId: TaskId): DependencyAnalysis
}
