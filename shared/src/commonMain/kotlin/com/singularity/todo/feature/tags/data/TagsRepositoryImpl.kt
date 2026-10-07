package com.singularity.todo.feature.tags.data

import com.singularity.todo.core.database.SyncColumns
import com.singularity.todo.core.database.TagDao
import com.singularity.todo.core.database.TagEntity
import com.singularity.todo.core.database.toInstant
import com.singularity.todo.core.database.toInstantOrNull
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.repository.assertCanWrite
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.core.sync.Hlc
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.TagsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.database.UnitOfWork

/**
 * Room-backed production [TagsRepository].
 */
class TagsRepositoryImpl(
    private val tagDao: TagDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
    private val syncRepository: SyncRepository,
    private val unitOfWork: UnitOfWork,
) : TagsRepository {

    // ── GenericUserScopedRepository ────────────────────────────────────────────

    override fun observeAll(): Flow<List<Tag>> = currentUser.observeForCurrentUser { uid ->
        tagDao.watchAll(uid.value).map { list -> list.map { it.toTag() } }
    }

    override fun observe(id: TagId): Flow<Tag?> = currentUser.observeForCurrentUser { uid ->
        tagDao.watchByIdForUser(id.value, uid.value).map { it?.toTag() }
    }

    override suspend fun get(id: TagId): Tag? {
        val uid = currentUser.scopedUserId.value
        return tagDao.getByIdForUser(id.value, uid.value)?.toTag()
    }

    override suspend fun create(item: Tag): Result<Tag> = runCatchingCancellable {
        unitOfWork.write {
            currentUser.assertCanWrite(entityId = item.syncId, entityUserId = item.userId)
            tagDao.upsert(item.toEntity())
            item.also { syncRepository.enqueue(it) }
        }
    }

    override suspend fun update(item: Tag): Result<Tag> = runCatchingCancellable {
        unitOfWork.write {
            currentUser.assertCanWrite(entityId = item.syncId, entityUserId = item.userId)
            tagDao.upsert(item.toEntity())
            item.also { syncRepository.enqueue(it) }
        }
    }

    // ── Remote apply (pull handler) ────────────────────────────────────────────

    override suspend fun upsert(tag: Tag): Tag {
        tagDao.upsert(tag.toEntity())
        return tag
    }

    override suspend fun delete(id: TagId): Result<Unit> = runCatchingCancellable {
        unitOfWork.write {
            val uid = currentUser.scopedUserId.value
            val ts = clock.now().toEpochMilliseconds()
            val rows = tagDao.softDeleteForUser(id.value, ts, uid.value)
            require(rows > 0) { "Tag $id not found or not owned by user" }
            // Deletion propagates as state (deletedAt) rather than a tombstone, so
            // the trashed tag itself is pushed and the server converges.
            //
            // "if it is still there" rather than an early return: the return would
            // have to cross the unit-of-work boundary, which is not an inline
            // function and cannot be returned from non-locally. Same behaviour —
            // a row that vanished between the delete and the re-read pushes
            // nothing — and the delete still commits alone rather than not at all.
            val row = tagDao.getByIdForUser(id.value, uid.value)
            if (row != null) {
                syncRepository.enqueue(row.toTag())
            }
        }
    }

    // Scoped: the unscoped `watchById` returns another profile's tag, and — because it
    // also lacks the `deleted_at` filter — a soft-deleted one. See TagsReadIsolationTest.
    override fun observeTag(id: TagId): Flow<Tag?> = currentUser.observeForCurrentUser { uid ->
        tagDao.watchByIdForUser(id.value, uid.value).map { it?.toTag() }
    }
}

/** Not private: [TagGroupRepositoryImpl] re-pushes released members on group delete. */
internal fun TagEntity.toTag(): Tag = Tag(
    id = TagId.fromString(id),
    name = name,
    color = color,
    createdAt = createdAt.toInstant(),
    updatedAt = updatedAt.toInstant(),
    groupId = groupId?.let { com.singularity.todo.feature.tags.domain.model.TagGroupId.fromString(it) },
    sortOrder = sortOrder,
    deletedAt = deletedAt.toInstantOrNull(),
    userId = UserId(userId),
    serverVersion = sync.serverVersion,
    hlc = sync.hlc?.let { Hlc(it) },
)

fun Tag.toEntity(): TagEntity = TagEntity(
    id = id.value,
    userId = userId.value,
    name = name,
    color = color,
    createdAt = createdAt.toEpochMilliseconds(),
    updatedAt = updatedAt.toEpochMilliseconds(),
    groupId = groupId?.value,
    sortOrder = sortOrder,
    deletedAt = deletedAt?.toEpochMilliseconds(),
    sync = SyncColumns(serverVersion = serverVersion, hlc = hlc?.encoded),
)
