package com.singularity.todo.feature.tasks.usecase

import com.singularity.todo.feature.tasks.TaskRepository
import com.singularity.todo.feature.tasks.TaskId

/**
 * Toggles a task's completed state.
 *
 * Provides a single injection point for future extensions (e.g., outbox events,
 * cascade effects on related subtasks, or recording completion timestamps).
 */
class ToggleTaskUseCase(private val repo: TaskRepository) {
    suspend operator fun invoke(id: TaskId): Result<Unit> = repo.toggleComplete(id)
}
