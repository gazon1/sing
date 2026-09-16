package com.singularity.todo.feature.checklist

import com.singularity.todo.core.platform.Clock
import kotlinx.coroutines.flow.first

class ChecklistUseCase(private val repository: ChecklistRepository, private val clock: Clock) {
    fun watchChecklist(taskId: String) = repository.watchByTask(taskId)

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
     * Toggles a checklist item's completed flag by its ID.
     * Fetches the current item state from the repository.
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

    suspend fun deleteItem(id: ChecklistItemId): Result<Unit> = repository.delete(id)

    suspend fun createBatch(taskId: String, items: List<ChecklistItem>): Result<Unit> =
        repository.createBatch(taskId, items)
}
