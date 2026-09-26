package com.singularity.todo.feature.checklist

import kotlinx.coroutines.flow.Flow

/**
 * Repository for [ChecklistItem] persistence attached to a task.
 *
 * Items are stored in Room as a flat list; ordering is maintained via [ChecklistItem.order].
 * All mutations return [Result] — errors are mapped from Room exceptions.
 *
 * @see com.singularity.todo.feature.checklist.domain.usecase.CreateChecklistItemsUseCase for batch creation.
 */
interface ChecklistRepository {
    fun watchByTask(taskId: String): Flow<List<ChecklistItem>>
    suspend fun addItem(taskId: String, title: String): Result<ChecklistItemId>
    suspend fun toggleItem(taskId: String, itemId: ChecklistItemId): Result<Unit>
    suspend fun upsert(item: ChecklistItem): Result<Unit>
    suspend fun delete(id: ChecklistItemId): Result<Unit>
    suspend fun createBatch(taskId: String, items: List<ChecklistItem>): Result<Unit>
}
