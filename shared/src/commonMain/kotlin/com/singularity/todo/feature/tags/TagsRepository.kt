package com.singularity.todo.feature.tags

import com.singularity.todo.core.database.TagDao
import com.singularity.todo.core.database.TagEntity
import com.singularity.todo.core.database.toInstant
import com.singularity.todo.core.database.toInstantOrNull
import com.singularity.todo.core.platform.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Contract for tags persistence.
 */
interface TagsRepository {
    fun watchTags(userId: String): Flow<List<Tag>>
    fun watchTag(id: TagId): Flow<Tag?>
    suspend fun create(tag: Tag): Result<Unit>
    suspend fun update(tag: Tag): Result<Unit>
    suspend fun delete(id: TagId): Result<Unit>
}

/**
 * Room-backed production [TagsRepository].
 */
class TagsRepositoryImpl(private val tagDao: TagDao, private val clock: Clock) : TagsRepository {
    override fun watchTags(userId: String): Flow<List<Tag>> = tagDao.watchAll(userId).map { list ->
        list.map { it.toTag() }
    }

    override fun watchTag(id: TagId): Flow<Tag?> = tagDao.watchById(id.value).map { it?.toTag() }

    override suspend fun create(tag: Tag): Result<Unit> = runCatching {
        tagDao.upsert(tag.toEntity())
    }

    override suspend fun update(tag: Tag): Result<Unit> = runCatching {
        tagDao.upsert(tag.toEntity())
    }

    override suspend fun delete(id: TagId): Result<Unit> = runCatching {
        val ts = clock.now().toEpochMilliseconds()
        tagDao.softDelete(id.value, ts)
    }
}

private fun TagEntity.toTag(): Tag = Tag(
    id = TagId.fromString(id),
    name = name,
    color = color,
    createdAt = createdAt.toInstant(),
    updatedAt = updatedAt.toInstant(),
    parentId = parentId?.let { TagId.fromString(it) },
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
    parentId = parentId?.value,
    sortOrder = sortOrder,
    deletedAt = deletedAt?.toEpochMilliseconds(),
)
