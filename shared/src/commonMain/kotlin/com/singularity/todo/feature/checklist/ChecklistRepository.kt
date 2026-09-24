package com.singularity.todo.feature.checklist

import kotlinx.coroutines.flow.Flow

/**
 * Repository for checklist item persistence.
 * Supports create, read, update, delete of checklist items attached to tasks.
 */
interface ChecklistRepository {
    fun watchByTask(taskId: String): Flow<List<ChecklistItem>>
    suspend fun addItem(taskId: String, title: String): Result<ChecklistItemId>
    suspend fun toggleItem(taskId: String, itemId: ChecklistItemId): Result<Unit>
    suspend fun upsert(item: ChecklistItem): Result<Unit>
    suspend fun delete(id: ChecklistItemId): Result<Unit>
    suspend fun createBatch(taskId: String, items: List<ChecklistItem>): Result<Unit>
}
