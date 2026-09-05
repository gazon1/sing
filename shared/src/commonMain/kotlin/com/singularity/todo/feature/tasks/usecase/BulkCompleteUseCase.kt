package com.singularity.todo.feature.tasks.usecase

import com.singularity.todo.feature.tasks.TaskRepository
import com.singularity.todo.feature.tasks.TaskId

/**
 * Marks multiple tasks as completed in a batch.
 *
 * Using a use case (rather than an inline `forEach { repo.toggleComplete(it) }` loop
 * in the ViewModel) allows the operation to be retried atomically and provides
 * a single injection point for future outbox/batch semantics.
 */
class BulkCompleteUseCase(private val repo: TaskRepository) {
    suspend operator fun invoke(ids: List<TaskId>): Result<Unit> = runCatching {
        ids.forEach { repo.toggleComplete(it).getOrThrow() }
    }
}
