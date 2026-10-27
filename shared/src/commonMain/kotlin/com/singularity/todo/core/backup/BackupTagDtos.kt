package com.singularity.todo.core.backup

import com.singularity.todo.core.database.SyncColumns
import com.singularity.todo.core.database.TagEntity
import com.singularity.todo.core.database.TagGroupEntity
import kotlinx.serialization.Serializable

/**
 * The tag rows of a payload: the tags, and the groups they hang off.
 */
// ─── TagDto ────────────────────────────────────────────────────────────────────

@Serializable
data class TagDto(
    val id: String,
    val name: String,
    val color: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val groupId: String? = null,
    val sortOrder: Int = 0,
    val deletedAt: Long? = null,
)

fun TagEntity.toDto(): TagDto =
    TagDto(
        id = id,
        name = name,
        color = color,
        createdAt = createdAt,
        updatedAt = updatedAt,
        groupId = groupId,
        sortOrder = sortOrder,
        deletedAt = deletedAt,
    )

fun TagDto.toEntity(userId: String): TagEntity =
    TagEntity(
        id = id,
        userId = userId,
        name = name,
        nameNormalized = name.trim().lowercase(),
        color = color,
        createdAt = createdAt,
        updatedAt = updatedAt,
        groupId = groupId,
        sortOrder = sortOrder,
        deletedAt = deletedAt,
        sync = SyncColumns(),
    )
// ─── TagGroupDto (MR-2) ──────────────────────────────────────────────────────

@Serializable
data class TagGroupDto(
    val id: String,
    val name: String,
    val color: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

fun TagGroupEntity.toDto(): TagGroupDto =
    TagGroupDto(
        id = id,
        name = name,
        color = color,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

fun TagGroupDto.toEntity(userId: String): TagGroupEntity =
    TagGroupEntity(
        id = id,
        userId = userId,
        name = name,
        color = color,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        sync = SyncColumns(),
    )
