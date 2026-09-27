package com.singularity.todo.feature.checklist

import kotlinx.coroutines.flow.Flow

/**
 * Repository for [ChecklistItem] persistence attached to a task.
 *
 * Items are stored in Room as a flat list; ordering is maintained via [ChecklistItem.order].
 * All mutations return [Result] — errors are mapped from Room exceptions.
 *
 * No use-case layer: batch creation is a plain [upsert] loop, so a pass-through
 * use case would be boilerplate. See `docs/decisions/2026-09-22-checklist-usecase-delete-and-dead-deps-cleanup.md`.
 */
interface ChecklistRepository {
    fun watchByTask(taskId: String): Flow<List<ChecklistItem>>
    suspend fun addItem(taskId: String, title: String): Result<ChecklistItemId>
    suspend fun toggleItem(taskId: String, itemId: ChecklistItemId): Result<Unit>
    suspend fun upsert(item: ChecklistItem): Result<Unit>
    suspend fun delete(id: ChecklistItemId): Result<Unit>
    suspend fun createBatch(taskId: String, items: List<ChecklistItem>): Result<Unit>
}
