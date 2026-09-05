package com.singularity.todo.feature.tasks.usecase

import com.singularity.todo.feature.tasks.TaskRepository
import com.singularity.todo.feature.tasks.TaskId

/**
 * Soft-deletes multiple tasks in a batch.
 */
class BulkDeleteUseCase(private val repo: TaskRepository) {
    suspend operator fun invoke(ids: List<TaskId>): Result<Unit> = runCatching {
        ids.forEach { repo.softDelete(it).getOrThrow() }
    }
}
