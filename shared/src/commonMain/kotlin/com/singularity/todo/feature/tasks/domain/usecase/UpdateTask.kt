package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.core.error.AppError
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlin.time.Clock

/**
 * Updates a task with automatic updatedAt timestamp injection.
 */
class UpdateTaskUseCase(private val repo: TaskRepository, private val clock: Clock) {
    /**
     * Full-entity update. Prefer [invoke(id, transform)] which re-reads before write
     * to avoid overwriting concurrent external changes.
     */
    @Deprecated(
        message = "Use invoke(id, transform) instead to avoid stale-snapshot overwrites",
        replaceWith = ReplaceWith("invoke(id, transform)"),
    )
    suspend operator fun invoke(task: Task): Result<Task> {
        val updated = task.copy(updatedAt = clock.now())
        return repo.update(updated)
    }

    /**
     * Read-modify-write update for atomic partial updates.
     * Re-reads current state before applying [transform] — safe against concurrent modifications.
     */
    suspend operator fun invoke(id: TaskId, transform: (Task) -> Task): Result<Task> {
        val current = repo.get(id)
            ?: return Result.failure(AppError.NotFound("Task $id not found", code = "task.not_found"))
        val updated = transform(current).copy(updatedAt = clock.now())
        return repo.update(updated)
    }
}
