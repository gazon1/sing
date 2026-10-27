package com.singularity.todo.feature.checklist.data

import com.singularity.todo.core.database.ChecklistDao
import com.singularity.todo.core.database.ChecklistItemEntity
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.checklist.domain.port.ChecklistRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import com.singularity.todo.core.error.runCatchingCancellable

class ChecklistRepositoryImpl(
    private val dao: ChecklistDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
) : ChecklistRepository {

    override fun watchByTask(taskId: String): Flow<List<ChecklistItem>> =
        currentUser.observeForCurrentUser { uid ->
            dao.watchByTaskForUser(taskId, uid.value).map { list -> list.map { it.toItem() } }
        }

    override suspend fun addItem(
        taskId: String,
        title: String,
        id: ChecklistItemId,
    ): Result<ChecklistItemId> = runCatchingCancellable {
        // Append, and append *after the last row*: the order is the number of
        // existing rows, which a delete followed by an add collides on — the new
        // row takes the number the deleted row had, and the DAO reads back with
        // `ORDER BY sort_order ASC`, so the tie is broken by rowid, which SQL
        // does not promise. The inline add-row on the task screen makes adds
        // frequent, which is exactly the usage that finds the collision.
        val sortOrder = dao.watchByTaskForUser(taskId, currentUser.scopedUserId.value.value)
            .first().maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
        val item = ChecklistItem(
            id = id,
            taskId = taskId,
            title = title,
            isCompleted = false,
            sortOrder = sortOrder,
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
        runCatchingCancellable {
            val uid = currentUser.scopedUserId.value.value
            val now = clock.now().toEpochMilliseconds()
            val existing = dao.watchByTaskForUser(taskId, uid).first().find { it.id == itemId.value }
                ?: throw IllegalArgumentException("Checklist item not found: $itemId")
            val rows = dao.toggleItem(itemId.value, !existing.isCompleted, now, now, actor, uid)
            require(rows > 0) { "Checklist item $itemId not found or not owned by current user" }
        }

    override suspend fun upsert(item: ChecklistItem): Result<Unit> = runCatchingCancellable {
        val now = clock.now().toEpochMilliseconds()
        val existing = dao.watchByTaskForUser(item.taskId, currentUser.scopedUserId.value.value)
            .first().find { it.id == item.id.value }
        dao.upsert(item.toEntity(now = now, existing = existing))
    }

    override suspend fun delete(id: ChecklistItemId): Result<Unit> = runCatchingCancellable {
        val rows = dao.deleteForUser(id.value, currentUser.scopedUserId.value.value)
        require(rows > 0) { "Checklist item $id not found or not owned by current user" }
    }

    override suspend fun createBatch(
        taskId: String,
        items: List<ChecklistItem>,
    ): Result<Unit> = runCatchingCancellable {
        val now = clock.now().toEpochMilliseconds()
        // Read existing items so we preserve their createdAt instead of overwriting with now
        val existing = dao.watchByTaskForUser(taskId, currentUser.scopedUserId.value.value)
            .first().associateBy { it.id }
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

private fun ChecklistItem.toEntity(now: Long, existing: ChecklistItemEntity? = null) = ChecklistItemEntity(
    id = id.value,
    taskId = taskId,
    title = title,
    isCompleted = isCompleted,
    sortOrder = sortOrder,
    createdAt = existing?.createdAt ?: now,
    updatedAt = now,
    checkedBy = checkedBy ?: existing?.checkedBy,
    checkedAt = checkedAt ?: existing?.checkedAt,
    rowVersion = existing?.rowVersion ?: 1,
)
