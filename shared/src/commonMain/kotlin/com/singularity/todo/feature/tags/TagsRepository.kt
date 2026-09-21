package com.singularity.todo.feature.tags

import com.singularity.todo.core.database.TagDao
import com.singularity.todo.core.database.TagEntity
import com.singularity.todo.core.database.toInstant
import com.singularity.todo.core.database.toInstantOrNull
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.repository.GenericUserScopedRepository
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Contract for tags persistence.
 */
interface TagsRepository : GenericUserScopedRepository<Tag, TagId> {

    // ─── Explicit userId overloads (kept for explicit-userId callers) ───────────
    fun watchAll(userId: String): Flow<List<Tag>>

    // ─── Domain methods ─────────────────────────────────────────────────────────

    /** Single tag observation by id (no user-filter, uses ambient current user). */
    fun observeTag(id: TagId): Flow<Tag?>
}

/**
 * Room-backed production [TagsRepository].
 */
class TagsRepositoryImpl(
    private val tagDao: TagDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
) : TagsRepository {

    // ── GenericUserScopedRepository ────────────────────────────────────────────

    override fun observeAll(): Flow<List<Tag>> =
        currentUser.observeForCurrentUser { uid ->
            tagDao.watchAll(uid.value).map { list -> list.map { it.toTag() } }
        }

    override fun observe(id: TagId): Flow<Tag?> =
        tagDao.watchById(id.value).map { it?.toTag() }

    override suspend fun get(id: TagId): Tag? = tagDao.watchById(id.value).first()?.toTag()

    override suspend fun create(tag: Tag): Result<Tag> = runCatching {
        tagDao.upsert(tag.toEntity())
        tag
    }

    override suspend fun update(tag: Tag): Result<Tag> = runCatching {
        tagDao.upsert(tag.toEntity())
        tag
    }

    override suspend fun delete(id: TagId): Result<Unit> = runCatching {
        val ts = clock.now().toEpochMilliseconds()
        tagDao.softDelete(id.value, ts)
    }

    // ─── Explicit userId overloads ──────────────────────────────────────────

    override fun watchAll(userId: String): Flow<List<Tag>> = tagDao.watchAll(userId).map { list ->
        list.map { it.toTag() }
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
)
