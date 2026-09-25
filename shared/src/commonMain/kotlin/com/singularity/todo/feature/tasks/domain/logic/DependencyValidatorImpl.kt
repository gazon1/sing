package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.graph.CycleDetector
import com.singularity.todo.core.graph.CycleError
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.DependencyValidator
import kotlinx.coroutines.flow.first

/**
 * Production [DependencyValidator] with full BFS cycle detection.
 *
 * Detects self-loop and transitive cycles (A→B→C→A) using [CycleDetector]
 * over the full dependency graph loaded from [TaskDao].
 *
 * The algorithm:
 * 1. Load all dependency edges for the current user
 * 2. Temporarily add `taskId → newDep` for each new dependency
 * 3. Run BFS from `taskId` — if any newDep reaches `taskId`, a cycle exists
 *
 * @see CycleDetector for the BFS implementation.
 */
class DependencyValidatorImpl(
    private val taskDao: TaskDao,
    private val currentUser: ProfileAwareCurrentUser,
) : DependencyValidator {

    override suspend fun assertNoCycles(
        taskId: TaskId,
        newDeps: Set<TaskId>,
    ): Result<Unit> {
        // Self-loop guard — fastest check, no DB round-trip.
        if (taskId.value in newDeps.map { it.value }) {
            return Result.failure(
                CycleError.SelfLoop(taskId.value),
            )
        }

        // Load full dependency graph for the current user.
        val allDeps = taskDao.listAllDependenciesForUser(currentUser.scopedUserId.value.value)
        @Suppress("UNCHECKED_CAST")
        val depMap: MutableMap<String, MutableSet<String>> = (allDeps
            .groupBy { it.taskId }
            .mapValues { (_, rows) -> rows.map { it.dependsOnTaskId }.toMutableSet() }
            as MutableMap<String, MutableSet<String>>)

        // Temporarily add the new edges: taskId depends on each newDep.
        val currentOfTaskId = depMap.getOrPut(taskId.value) { mutableSetOf() }
        currentOfTaskId.addAll(newDeps.map { it.value })

        // Build the BFS edges function using the augmented map.
        val bfsResult = CycleDetector.detect(
            node = taskId.value,
            newParent = newDeps.firstOrNull()?.value ?: return Result.success(Unit),
            edges = { id -> depMap[id].orEmpty() },
        )

        // If the first newDep causes a cycle, all of them would — report the cycle.
        bfsResult.onFailure { return Result.failure(it) }
        // All newDeps are safe. Verify each individually to give precise error messages.
        for (newDep in newDeps) {
            val result = CycleDetector.detect(
                node = taskId.value,
                newParent = newDep.value,
                edges = { id -> depMap[id].orEmpty() },
            )
            result.onFailure { return Result.failure(it) }
        }
        return Result.success(Unit)
    }
}
