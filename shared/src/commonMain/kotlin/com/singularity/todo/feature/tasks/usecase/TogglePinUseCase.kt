package com.singularity.todo.feature.tasks.usecase

import com.singularity.todo.feature.tasks.TaskRepository
import com.singularity.todo.feature.tasks.TaskId

/**
 * Toggles a task's pinned state.
 */
class TogglePinUseCase(private val repo: TaskRepository) {
    suspend operator fun invoke(id: TaskId): Result<Unit> = repo.togglePinned(id)
}
