package com.singularity.todo.core.backup

import com.singularity.todo.core.attachments.AttachmentEntity
import com.singularity.todo.core.database.NoteEntity
import com.singularity.todo.core.database.ProjectEntity
import com.singularity.todo.core.database.TagEntity
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.TaskTagCrossRef
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

// ─── TaskDto ───────────────────────────────────────────────────────────────────

@Serializable
data class TaskDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val priority: String = "None",
    val kind: String = "Task",
    val projectId: String? = null,
    val dueDate: String? = null,
    val dueTime: String? = null,
    val completedAt: Long? = null,
    val someday: Boolean = false,
    val archivedAt: Long? = null,
    val isPinned: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)

fun TaskEntity.toDto(): TaskDto = TaskDto(
    id = id, title = title, description = description,
    priority = priority.name, kind = kind.name,
    projectId = projectId, dueDate = dueDate, dueTime = dueTime,
    completedAt = completedAt, someday = someday,
    archivedAt = archivedAt, isPinned = isPinned,
    createdAt = createdAt, updatedAt = updatedAt
)

fun TaskDto.toEntity(userId: String): TaskEntity = TaskEntity(
    id = id, title = title, description = description,
    priority = com.singularity.todo.feature.tasks.TaskPriority.valueOf(priority),
    kind = com.singularity.todo.feature.tasks.TaskKind.valueOf(kind),
    projectId = projectId, dueDate = dueDate, dueTime = dueTime,
    completedAt = completedAt, someday = someday,
    archivedAt = archivedAt, isPinned = isPinned,
    createdAt = createdAt, updatedAt = updatedAt,
    userId = userId,
    serverVersion = 0L, syncStatus = "LOCAL_ONLY",
    syncError = null, lastSyncedAt = null, deviceId = null, hlc = null
)

// ─── NoteDto ───────────────────────────────────────────────────────────────────

@Serializable
data class NoteDto(
    val id: String,
    val title: String = "",
    val bodyMarkdown: String? = null,
    val bodyHtml: String? = null,
    val isFolder: Boolean = false,
    val parentNoteId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val archivedAt: Long? = null
)

fun NoteEntity.toDto(): NoteDto = NoteDto(
    id = id, title = title, bodyMarkdown = bodyMarkdown,
    bodyHtml = bodyHtml, isFolder = isFolder,
    parentNoteId = parentNoteId, createdAt = createdAt,
    updatedAt = updatedAt, deletedAt = deletedAt, archivedAt = archivedAt
)

fun NoteDto.toEntity(userId: String): NoteEntity = NoteEntity(
    id = id, userId = userId, title = title,
    bodyMarkdown = bodyMarkdown, bodyHtml = bodyHtml,
    isFolder = isFolder, parentNoteId = parentNoteId,
    createdAt = createdAt, updatedAt = updatedAt,
    deletedAt = deletedAt, archivedAt = archivedAt,
    serverVersion = 0L, syncStatus = "LOCAL_ONLY",
    syncError = null, lastSyncedAt = null, deviceId = null, hlc = null
)

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
    val isNotebook: Boolean = false,
    val externalId: String? = null
)

fun ProjectEntity.toDto(): ProjectDto = ProjectDto(
    id = id, name = name, color = color, icon = icon,
    description = description, createdAt = createdAt, updatedAt = updatedAt,
    isDefault = isDefault, dueDate = dueDate, team = team,
    isDeleted = isDeleted, deletedAt = deletedAt, parentId = parentId,
    sortOrder = sortOrder, isNotebook = isNotebook, externalId = externalId
)

fun ProjectDto.toEntity(userId: String): ProjectEntity = ProjectEntity(
    id = id, userId = userId, name = name, color = color,
    icon = icon, description = description,
    createdAt = createdAt, updatedAt = updatedAt,
    isDefault = isDefault, dueDate = dueDate, team = team,
    isDeleted = isDeleted, deletedAt = deletedAt, parentId = parentId,
    sortOrder = sortOrder, isNotebook = isNotebook, externalId = externalId,
    serverVersion = 0L, syncStatus = "LOCAL_ONLY",
    syncError = null, lastSyncedAt = null, deviceId = null, hlc = null
)

// ─── TagDto ────────────────────────────────────────────────────────────────────

@Serializable
data class TagDto(
    val id: String,
    val name: String,
    val color: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val parentId: String? = null,
    val sortOrder: Int = 0,
    val deletedAt: Long? = null
)

fun TagEntity.toDto(): TagDto = TagDto(
    id = id, name = name, color = color,
    createdAt = createdAt, updatedAt = updatedAt,
    parentId = parentId, sortOrder = sortOrder, deletedAt = deletedAt
)

fun TagDto.toEntity(userId: String): TagEntity = TagEntity(
    id = id, userId = userId, name = name, color = color,
    createdAt = createdAt, updatedAt = updatedAt,
    parentId = parentId, sortOrder = sortOrder, deletedAt = deletedAt,
    serverVersion = 0L, syncStatus = "LOCAL_ONLY",
    syncError = null, lastSyncedAt = null, deviceId = null, hlc = null
)

// ─── AttachmentDto ─────────────────────────────────────────────────────────────

@Serializable
data class AttachmentDto(
    val id: String,
    val taskId: String,
    val type: String,
    val url: String? = null,
    val title: String = "",
    val localPath: String? = null,
    val remoteUrl: String? = null,
    val fileSizeBytes: Long = 0L,
    val mimeType: String? = null,
    val checksum: String? = null,
    val syncStatus: String = "Pending",
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)

fun AttachmentEntity.toDto(): AttachmentDto = AttachmentDto(
    id = id, taskId = taskId, type = type,
    url = url, title = title, localPath = localPath,
    remoteUrl = remoteUrl, fileSizeBytes = fileSizeBytes,
    mimeType = mimeType, checksum = checksum,
    syncStatus = syncStatus, createdAt = createdAt,
    updatedAt = updatedAt, deletedAt = deletedAt
)

fun AttachmentDto.toEntity(userId: String): AttachmentEntity = AttachmentEntity(
    id = id, taskId = taskId, userId = userId, type = type,
    url = url, title = title, localPath = localPath,
    remoteUrl = remoteUrl, fileSizeBytes = fileSizeBytes,
    mimeType = mimeType, checksum = checksum,
    syncStatus = syncStatus, createdAt = createdAt,
    updatedAt = updatedAt, deletedAt = deletedAt,
    serverVersion = 0L, hlc = null
)

// ─── TaskTagDto ───────────────────────────────────────────────────────────────

@Serializable
data class TaskTagDto(
    val taskId: String,
    val tagId: String
)

fun TaskTagCrossRef.toDto(): TaskTagDto = TaskTagDto(taskId = taskId, tagId = tagId)

fun TaskTagDto.toEntity(): TaskTagCrossRef = TaskTagCrossRef(taskId = taskId, tagId = tagId)
