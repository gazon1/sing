package com.singularity.todo.core.backup

import com.singularity.todo.core.database.ProfileEntity
import kotlinx.serialization.Serializable

/**
 * The profile rows of a payload.
 */
// ─── ProfileDto (MR-2) ────────────────────────────────────────────────────────

@Serializable
data class ProfileDto(
    val id: String,
    val name: String,
    val emoji: String,
    val colorIdx: Int,
    val isDefault: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val userId: String? = null,
)

fun ProfileEntity.toDto(): ProfileDto =
    ProfileDto(
        id = id,
        name = name,
        emoji = emoji,
        colorIdx = colorIdx,
        isDefault = isDefault,
        createdAt = createdAt,
        updatedAt = updatedAt,
        userId = userId,
    )

// Profiles are restored with their original userId intact — the owner stays the same across a restore.
fun ProfileDto.toEntity(): ProfileEntity =
    ProfileEntity(
        id = id,
        name = name,
        emoji = emoji,
        colorIdx = colorIdx,
        isDefault = isDefault,
        createdAt = createdAt,
        updatedAt = updatedAt,
        userId = userId,
    )
