package com.singularity.todo.feature.tags

import com.singularity.todo.core.database.TagDao
import com.singularity.todo.core.database.TagEntity
import com.singularity.todo.core.platform.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TagsRepository(
    private val tagDao: TagDao,
    private val clock: Clock
) {
    fun watchTags(userId: String): Flow<List<Tag>> {
        return tagDao.watchAll(userId).map { list -> list.map { it.toTag() } }
    }

    fun watchTag(id: TagId): Flow<Tag?> {
        return tagDao.watchById(id.value).map { it?.toTag() }
    }

    suspend fun create(tag: Tag): Result<Unit> = runCatching {
        tagDao.upsert(tag.toEntity())
    }

    suspend fun update(tag: Tag): Result<Unit> = runCatching {
        tagDao.upsert(tag.toEntity())
    }

    suspend fun delete(id: TagId): Result<Unit> = runCatching {
        val ts = clock.now().toEpochMilliseconds()
        tagDao.softDelete(id.value, ts)
    }
}

private fun TagEntity.toTag(): Tag = Tag(
    id = TagId.fromString(id),
    name = name,
    color = color,
    createdAt = kotlinx.datetime.Instant.fromEpochMilliseconds(createdAt),
    updatedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(updatedAt),
    parentId = parentId?.let { TagId.fromString(it) },
    sortOrder = sortOrder,
    deletedAt = deletedAt?.let { kotlinx.datetime.Instant.fromEpochMilliseconds(it) },
    userId = userId
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
    deletedAt = deletedAt?.toEpochMilliseconds()
)
