package com.singularity.todo.feature.tasks.usecase

import com.singularity.todo.feature.tasks.TaskRepository
import com.singularity.todo.feature.tasks.TaskId

/**
 * Soft-deletes a task.
 *
 * Unlike a raw `taskRepo.softDelete(id)` call in a ViewModel, this use case
 * provides a single injection point for future extensions (e.g., outbox events,
 * audit logging, or validation that the task is not part of an active session).
 */
class DeleteTaskUseCase(private val repo: TaskRepository) {
    suspend operator fun invoke(id: TaskId): Result<Unit> = repo.softDelete(id)
}
