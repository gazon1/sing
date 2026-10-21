package com.singularity.todo.feature.tags

import com.singularity.todo.core.database.TagEntity
import com.singularity.todo.core.database.toInstant
import com.singularity.todo.core.database.toInstantOrNull
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.Hlc
import com.singularity.todo.feature.tags.domain.model.TagGroupId

/**
 * Entity/domain mappers for tags.
 *
 * Not private: the tag group repository re-pushes released members on group
 * delete, and the search index reads tags through the same mapper — a tag
 * found by a search has to be the same tag a repository would return.
 */
internal fun TagEntity.toTag(): Tag = Tag(
    id = TagId.fromString(id),
    name = name,
    color = color,
    createdAt = createdAt.toInstant(),
    updatedAt = updatedAt.toInstant(),
    groupId = groupId?.let { TagGroupId.fromString(it) },
    sortOrder = sortOrder,
    deletedAt = deletedAt.toInstantOrNull(),
    userId = UserId(userId),
    serverVersion = sync.serverVersion,
    hlc = sync.hlc?.let { Hlc(it) },
)
