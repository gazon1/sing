package com.singularity.todo.feature.checklist.data

import com.singularity.todo.core.database.ChecklistDao
import com.singularity.todo.core.database.ChecklistItemEntity
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.checklist.domain.port.ChecklistRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

class ChecklistRepositoryImpl(
    private val dao: ChecklistDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
) : ChecklistRepository {

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
                checkedBy = null,
                checkedAt = null,
                rowVersion = 1,
            ),
        )
        item.id
    }

    override suspend fun toggleItem(taskId: String, itemId: ChecklistItemId, actor: String): Result<Unit> =
        runCatching {
            val uid = currentUser.scopedUserId.value.value
            val now = clock.now().toEpochMilliseconds()
            val existing = dao.watchByTask(taskId).first().find { it.id == itemId.value }
                ?: throw IllegalArgumentException("Checklist item not found: $itemId")
            val rows = dao.toggleItem(itemId.value, !existing.isCompleted, now, now, actor, uid)
            require(rows > 0) { "Checklist item $itemId not found or not owned by current user" }
        }

    override suspend fun upsert(item: ChecklistItem): Result<Unit> = runCatching {
        dao.upsert(item.toEntity())
    }

    override suspend fun delete(id: ChecklistItemId): Result<Unit> = runCatching {
        val rows = dao.deleteForUser(id.value, currentUser.scopedUserId.value.value)
        require(rows > 0) { "Checklist item $id not found or not owned by current user" }
    }

    override suspend fun createBatch(taskId: String, items: List<ChecklistItem>): Result<Unit> = runCatching {
        val now = clock.now().toEpochMilliseconds()
        // Read existing items so we preserve their createdAt instead of overwriting with now
        val existing = dao.watchByTask(taskId).first().associateBy { it.id }
        items.forEachIndexed { index, item ->
            val existingItem = existing[item.id.value]
            dao.upsert(
                ChecklistItemEntity(
                    id = item.id.value,
                    taskId = taskId,
                    title = item.title,
                    isCompleted = item.isCompleted,
                    sortOrder = index,
                    createdAt = existingItem?.createdAt ?: now,
                    updatedAt = now,
                    checkedBy = item.checkedBy ?: existingItem?.checkedBy,
                    checkedAt = item.checkedAt ?: existingItem?.checkedAt,
                    rowVersion = existingItem?.rowVersion ?: 1,
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
    checkedBy = checkedBy,
    checkedAt = checkedAt,
)

private fun ChecklistItem.toEntity() = ChecklistItemEntity(
    id = id.value,
    taskId = taskId,
    title = title,
    isCompleted = isCompleted,
    sortOrder = sortOrder,
    createdAt = 0L, // filled by repository
    updatedAt = 0L,
    checkedBy = checkedBy,
    checkedAt = checkedAt,
    rowVersion = 1,
)
