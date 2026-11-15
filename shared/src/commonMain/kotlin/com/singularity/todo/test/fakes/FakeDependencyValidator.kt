package com.singularity.todo.test.fakes

import com.singularity.todo.feature.tasks.domain.model.DependencyAnalysis
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.DependencyValidator

/**
 * A [DependencyValidator] that refuses exactly the candidates a test names.
 *
 * The graph is not modelled here: the real validator is tested against a real
 * database in `DependencyValidatorTest`, and this stand-in exists so a picker
 * test can ask "what does the sheet do when the domain says no" without
 * reimplementing cycle traversal.
 */
class FakeDependencyValidator(private val rejected: Set<TaskId> = emptySet()) : DependencyValidator {
    override suspend fun assertNoCycles(
        taskId: TaskId,
        newDeps: Set<TaskId>,
    ): Result<Unit> {
        val refused = newDeps.firstOrNull { it == taskId || it in rejected }
        return if (refused == null) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalArgumentException("Task $taskId cannot be related to $refused"))
        }
    }

    override suspend fun analyzeDependencies(taskId: TaskId): DependencyAnalysis =
        DependencyAnalysis(containsCycle = false, cycleEdges = emptyList())
}
