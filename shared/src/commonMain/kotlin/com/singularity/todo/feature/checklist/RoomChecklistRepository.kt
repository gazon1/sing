package com.singularity.todo.feature.checklist

import com.singularity.todo.core.database.ChecklistDao
import com.singularity.todo.core.database.ChecklistItemEntity
import com.singularity.todo.core.platform.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomChecklistRepository(private val dao: ChecklistDao, private val clock: Clock) : ChecklistRepository {

    override fun watchByTask(taskId: String): Flow<List<ChecklistItem>> =
        dao.watchByTask(taskId).map { list -> list.map { it.toItem() } }

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
