package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository

/**
 * Updates a task with automatic updatedAt timestamp injection.
 */
class UpdateTaskUseCase(private val repo: TaskRepository, private val clock: Clock) {
    /** Full-entity update. */
    suspend operator fun invoke(task: Task): Result<Task> {
        val updated = task.copy(updatedAt = clock.now())
        return repo.update(updated)
    }

    /**
     * Read-modify-write update for atomic partial updates.
     */
    suspend operator fun invoke(id: TaskId, transform: (Task) -> Task): Result<Task> {
        val current = repo.get(id)
            ?: return Result.failure(AppError.NotFound("Task $id not found"))
        val updated = transform(current).copy(updatedAt = clock.now())
        return repo.update(updated)
    }
}
