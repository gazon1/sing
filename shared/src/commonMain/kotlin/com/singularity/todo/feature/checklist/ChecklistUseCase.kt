package com.singularity.todo.feature.checklist

import kotlinx.coroutines.flow.first

/**
 * Use case for checklist item mutations that require domain logic:
 * ID generation, entity construction with defaults, and read-then-update with 404.
 *
 * Pass-through operations (watch, delete) are called directly on [ChecklistRepository]
 * by consumers — consistent with the per-entity pattern used throughout the codebase
 * (e.g. individual task mutations go directly to [com.singularity.todo.feature.tasks.domain.port.TaskRepository]).
 */
class ChecklistUseCase(private val repository: ChecklistRepository) {

    /**
     * Adds a new checklist item with generated ID and default values.
     * Real logic: [ChecklistItemId.generate], entity construction, [ChecklistRepository.upsert].
     */
    suspend fun addItem(taskId: String, title: String): Result<ChecklistItemId> = runCatching {
        val item = ChecklistItem(
            id = ChecklistItemId.generate(),
            taskId = taskId,
            title = title,
            isCompleted = false,
            sortOrder = 0,
        )
        repository.upsert(item).getOrThrow()
        item.id
    }

    /**
     * Toggles the completed flag by reconstructing the item with the opposite flag.
     * Real logic: entity reconstruction (preserves sortOrder/title/taskId).
     */
    suspend fun toggleItem(
        id: ChecklistItemId,
        currentTitle: String,
        taskId: String,
        currentlyCompleted: Boolean,
    ): Result<Unit> = runCatching {
        repository.upsert(
            ChecklistItem(
                id = id,
                taskId = taskId,
                title = currentTitle,
                isCompleted = !currentlyCompleted,
                sortOrder = 0,
            ),
        ).getOrThrow()
    }

    /**
     * Toggles the completed flag by reading the current item then updating it.
     * Real logic: read-modify-write with 404 when the item is not found.
     */
    suspend fun toggleItem(taskId: String, itemId: ChecklistItemId): Result<Unit> = runCatching {
        val allItems = repository.watchByTask(taskId).first()
        val item = allItems.find { it.id == itemId }
            ?: throw IllegalArgumentException("Checklist item not found: $itemId")
        repository.upsert(
            ChecklistItem(
                id = item.id,
                taskId = item.taskId,
                title = item.title,
                isCompleted = !item.isCompleted,
                sortOrder = item.sortOrder,
            ),
        ).getOrThrow()
    }
}
