package com.singularity.todo.feature.checklist

import com.singularity.todo.core.database.ChecklistDao
import com.singularity.todo.core.database.ChecklistItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

class RoomChecklistRepository(private val dao: ChecklistDao, private val clock: Clock) : ChecklistRepository {

    override fun watchByTask(taskId: String): Flow<List<ChecklistItem>> =
        dao.watchByTask(taskId).map { list -> list.map { it.toItem() } }

    override suspend fun addItem(taskId: String, title: String): Result<ChecklistItemId> = runCatching {
        val item = ChecklistItem(
            id = ChecklistItemId.generate(),
            taskId = taskId,
            title = title,
            isCompleted = false,
            sortOrder = 0,
        )
        val now = clock.now().toEpochMilliseconds()
        dao.upsert(
            ChecklistItemEntity(
                id = item.id.value,
                taskId = item.taskId,
                title = item.title,
                isCompleted = item.isCompleted,
                sortOrder = item.sortOrder,
                createdAt = now,
                updatedAt = now,
            ),
        )
        item.id
    }

    override suspend fun toggleItem(taskId: String, itemId: ChecklistItemId): Result<Unit> = runCatching {
        val allItems = dao.watchByTask(taskId).first()
        val existing = allItems.find { it.id == itemId.value }
            ?: throw IllegalArgumentException("Checklist item not found: $itemId")
        val now = clock.now().toEpochMilliseconds()
        dao.upsert(
            ChecklistItemEntity(
                id = existing.id,
                taskId = existing.taskId,
                title = existing.title,
                isCompleted = !existing.isCompleted,
                sortOrder = existing.sortOrder,
                createdAt = existing.createdAt,
                updatedAt = now,
            ),
        )
    }

    override suspend fun upsert(item: ChecklistItem): Result<Unit> = runCatching {
        dao.upsert(item.toEntity())
    }

    override suspend fun delete(id: ChecklistItemId): Result<Unit> = runCatching {
        dao.delete(id.value)
    }

    override suspend fun createBatch(taskId: String, items: List<ChecklistItem>): Result<Unit> = runCatching {
        val now = clock.now().toEpochMilliseconds()
        items.forEachIndexed { index, item ->
            dao.upsert(
                ChecklistItemEntity(
                    id = item.id.value,
                    taskId = taskId,
                    title = item.title,
                    isCompleted = item.isCompleted,
                    sortOrder = index,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }
}

private fun ChecklistItemEntity.toItem() = ChecklistItem(
    id = ChecklistItemId.fromString(id),
    taskId = taskId,
    title = title,
    isCompleted = isCompleted,
    sortOrder = sortOrder,
)

private fun ChecklistItem.toEntity() = ChecklistItemEntity(
    id = id.value,
    taskId = taskId,
    title = title,
    isCompleted = isCompleted,
    sortOrder = sortOrder,
    createdAt = 0L, // filled by repository
    updatedAt = 0L,
)
