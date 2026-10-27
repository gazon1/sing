package com.singularity.todo.core.backup

import com.singularity.todo.core.database.ProjectEntity
import com.singularity.todo.core.database.ProjectInheritedTagGroupCrossRef
import com.singularity.todo.core.database.ProjectReminderEntity
import com.singularity.todo.core.database.SyncColumns
import kotlinx.serialization.Serializable

/**
 * The project rows of a payload: the project, its reminders, and the tag groups it inherits.
 */
// ─── ProjectDto ───────────────────────────────────────────────────────────────

@Serializable
data class ProjectDto(
    val id: String,
    val name: String,
    val color: Int,
    val icon: String? = null,
    val description: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val isDefault: Boolean = false,
    val dueDate: String? = null,
    val team: String? = null,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val parentId: String? = null,
    val sortOrder: Int = 0,
    val idempotencyKey: String? = null,
    val externalId: String? = null,
    /** When the project's work was declared finished; null means the project is open. */
    val closedAt: Long? = null,
)

fun ProjectEntity.toDto(): ProjectDto =
    ProjectDto(
        id = id,
        name = name,
        color = color,
        icon = icon,
        description = description,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isDefault = isDefault,
        dueDate = dueDate,
        team = team,
        isDeleted = isDeleted,
        deletedAt = deletedAt,
        parentId = parentId,
        sortOrder = sortOrder,
        idempotencyKey = idempotencyKey,
        externalId = externalId,
        closedAt = closedAt,
    )

fun ProjectDto.toEntity(userId: String): ProjectEntity =
    ProjectEntity(
        id = id,
        userId = userId,
        name = name,
        color = color,
        icon = icon,
        description = description,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isDefault = isDefault,
        dueDate = dueDate,
        team = team,
        isDeleted = isDeleted,
        deletedAt = deletedAt,
        parentId = parentId,
        sortOrder = sortOrder,
        idempotencyKey = idempotencyKey,
        externalId = externalId,
        closedAt = closedAt,
        sync = SyncColumns(),
    )
// ─── ProjectReminderDto (MR-2) ──────────────────────────────────────────────

@Serializable
data class ProjectReminderDto(
    val id: String,
    val projectId: String,
    val fireAt: Long,
    val lastFiredAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

fun ProjectReminderEntity.toDto(): ProjectReminderDto =
    ProjectReminderDto(
        id = id,
        projectId = projectId,
        fireAt = fireAt,
        lastFiredAt = lastFiredAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

fun ProjectReminderDto.toEntity(userId: String): ProjectReminderEntity =
    ProjectReminderEntity(
        id = id,
        projectId = projectId,
        userId = userId,
        fireAt = fireAt,
        lastFiredAt = lastFiredAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
// ─── ProjectTagGroupDto (MR-2: project_tag_groups join table) ─────────────────

@Serializable
data class ProjectTagGroupDto(val projectId: String, val tagGroupId: String)

fun ProjectInheritedTagGroupCrossRef.toDto(): ProjectTagGroupDto =
    ProjectTagGroupDto(
        projectId = projectId,
        tagGroupId = tagGroupId,
    )

fun ProjectTagGroupDto.toEntity(): ProjectInheritedTagGroupCrossRef =
    ProjectInheritedTagGroupCrossRef(projectId = projectId, tagGroupId = tagGroupId)
