package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository

/**
 * Single injection point for bulk task mutation operations.
 * Individual mutations (delete/toggle/togglePin) are called directly on [TaskRepository].
 *
 * Bulk operations enforce atomicity: fail-fast if any ID doesn't exist,
 * before mutating anything. This lives here so VMs stay thin.
 */
class TaskMutationsUseCase(private val repo: TaskRepository) {

    suspend fun bulkComplete(ids: List<TaskId>): Result<Unit> = runCatching {
        // Atomic: fail-fast if any ID doesn't exist, before mutating anything.
        ids.forEach { id ->
            if (!repo.exists(id)) throw IllegalArgumentException("Task $id not found")
        }
        ids.forEach { repo.toggleComplete(it).getOrThrow() }
    }

    suspend fun bulkDelete(ids: List<TaskId>): Result<Unit> = runCatching {
        ids.forEach { id ->
            if (!repo.exists(id)) throw IllegalArgumentException("Task $id not found")
        }
        ids.forEach { repo.softDelete(it).getOrThrow() }
    }
}
