package com.singularity.todo.feature.checklist

import kotlinx.coroutines.flow.Flow

interface ChecklistRepository {
    fun watchByTask(taskId: String): Flow<List<ChecklistItem>>
    suspend fun upsert(item: ChecklistItem): Result<Unit>
    suspend fun delete(id: ChecklistItemId): Result<Unit>
    suspend fun createBatch(taskId: String, items: List<ChecklistItem>): Result<Unit>
}
