package com.singularity.todo.feature.tasks.usecase

import com.singularity.todo.feature.tasks.TaskId
import com.singularity.todo.feature.tasks.TaskRepository

/**
 * Single injection point for all task mutation operations.
 * Replaces [DeleteTaskUseCase], [ToggleTaskUseCase], [TogglePinUseCase],
 * [BulkCompleteUseCase], and [BulkDeleteUseCase].
 *
 * Each method delegates to the corresponding [TaskRepository] method.
 * Keeping mutations behind a use case allows future extensions
 * (outbox events, audit logging, cascade effects) without touching VMs.
 */
class TaskMutationsUseCase(private val repo: TaskRepository) {

    suspend fun delete(id: TaskId): Result<Unit> = repo.softDelete(id)

    suspend fun toggle(id: TaskId): Result<Unit> = repo.toggleComplete(id)

    suspend fun togglePin(id: TaskId): Result<Unit> = repo.togglePinned(id)

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
