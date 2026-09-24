package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.DependencyValidator

/**
 * Production [DependencyValidator].
 *
 * v1 checks only for self-loop (task depending on itself).
 * Full BFS cycle detection (A→B→C→A) is tracked in issue tracker.
 */
class DependencyValidatorImpl(
    private val taskDao: TaskDao,
) : DependencyValidator {

    override suspend fun assertNoCycles(
        taskId: TaskId,
        newDeps: Set<TaskId>,
    ): Result<Unit> {
        // Self-loop guard — simplest possible cycle.
        if (taskId.value in newDeps.map { it.value }) {
            return Result.failure(IllegalArgumentException("Task cannot depend on itself: ${taskId.value}"))
        }
        return Result.success(Unit)
    }
}
