package com.singularity.todo.feature.tags.data

import com.singularity.todo.core.database.SyncColumns
import com.singularity.todo.core.database.TagDao
import com.singularity.todo.core.database.TagEntity
import com.singularity.todo.core.database.toInstant
import com.singularity.todo.core.database.toInstantOrNull
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.core.sync.Hlc
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.TagsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed production [TagsRepository].
 */
class TagsRepositoryImpl(
    private val tagDao: TagDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
    private val syncRepository: SyncRepository,
) : TagsRepository {

    // ── GenericUserScopedRepository ────────────────────────────────────────────

    override fun observeAll(): Flow<List<Tag>> =
        currentUser.observeForCurrentUser { uid ->
            tagDao.watchAll(uid.value).map { list -> list.map { it.toTag() } }
        }

    override fun observe(id: TagId): Flow<Tag?> =
        currentUser.observeForCurrentUser { uid ->
            tagDao.watchByIdForUser(id.value, uid.value).map { it?.toTag() }
        }

    override suspend fun get(id: TagId): Tag? {
        val uid = currentUser.scopedUserId.value
        return tagDao.getByIdForUser(id.value, uid.value)?.toTag()
    }

    override suspend fun create(item: Tag): Result<Tag> = runCatching {
        tagDao.upsert(item.toEntity())
        item.also { syncRepository.enqueue(it) }
    }

    override suspend fun update(item: Tag): Result<Tag> = runCatching {
        tagDao.upsert(item.toEntity())
        item.also { syncRepository.enqueue(it) }
    }

    // ── Remote apply (pull handler) ────────────────────────────────────────────

    override suspend fun upsert(tag: Tag): Tag {
        tagDao.upsert(tag.toEntity())
        return tag
    }

    override suspend fun delete(id: TagId): Result<Unit> = runCatching {
        val uid = currentUser.scopedUserId.value
        val ts = clock.now().toEpochMilliseconds()
        val rows = tagDao.softDeleteForUser(id.value, ts, uid.value)
        require(rows > 0) { "Tag $id not found or not owned by user" }
    }

    override fun observeTag(id: TagId): Flow<Tag?> = tagDao.watchById(id.value).map { it?.toTag() }
}

private fun TagEntity.toTag(): Tag = Tag(
    id = TagId.fromString(id),
    name = name,
    color = color,
    createdAt = createdAt.toInstant(),
    updatedAt = updatedAt.toInstant(),
    // parentId is dead schema — intentionally ignored (superseded by tag_groups in MR-3).
    // Writing null here keeps the deprecation harmless and ensures round-trip stability.
    parentId = null,
    sortOrder = sortOrder,
    deletedAt = deletedAt.toInstantOrNull(),
    userId = userId,
    serverVersion = sync.serverVersion,
    hlc = sync.hlc?.let { Hlc(it) },
)

fun Tag.toEntity(): TagEntity = TagEntity(
    id = id.value,
    userId = userId,
    name = name,
    color = color,
    createdAt = createdAt.toEpochMilliseconds(),
    updatedAt = updatedAt.toEpochMilliseconds(),
    // parentId is dead schema — always written as null (superseded by tag_groups in MR-3).
    parentId = null,
    sortOrder = sortOrder,
    deletedAt = deletedAt?.toEpochMilliseconds(),
    sync = SyncColumns(serverVersion = serverVersion, hlc = hlc?.encoded),
)
