package com.singularity.todo.feature.checklist

import com.singularity.todo.core.platform.Clock

class ChecklistUseCase(
    private val repository: ChecklistRepository,
    private val clock: Clock,
) {
    fun watchChecklist(taskId: String) = repository.watchByTask(taskId)

    suspend fun addItem(taskId: String, title: String): Result<ChecklistItemId> = runCatching {
        val now = clock.now().toEpochMilliseconds()
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

    suspend fun toggleItem(id: ChecklistItemId, currentTitle: String, taskId: String, currentlyCompleted: Boolean): Result<Unit> = runCatching {
        val now = clock.now().toEpochMilliseconds()
        repository.upsert(
            ChecklistItem(
                id = id,
                taskId = taskId,
                title = currentTitle,
                isCompleted = !currentlyCompleted,
                sortOrder = 0,
            )
        ).getOrThrow()
    }

    suspend fun deleteItem(id: ChecklistItemId): Result<Unit> = repository.delete(id)

    suspend fun createBatch(taskId: String, items: List<ChecklistItem>): Result<Unit> =
        repository.createBatch(taskId, items)
}
