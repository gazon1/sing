package com.singularity.todo.core.backup

import com.singularity.todo.core.attachments.AttachmentEntity
import com.singularity.todo.core.database.AgendaViewEntity
import com.singularity.todo.core.database.LocalTimeFormats
import com.singularity.todo.core.database.NoteEntity
import com.singularity.todo.core.database.ProjectEntity
import com.singularity.todo.core.database.SyncColumns
import com.singularity.todo.core.database.TagEntity
import com.singularity.todo.core.database.TaskDependencyCrossRef
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.TaskTagCrossRef
import com.singularity.todo.core.database.toLocalTimeOrNull
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Deserializes a JSON array column (`"[]"`, `"["a","b"]"`) into a list.
 * Tolerates legacy/corrupt payloads instead of aborting a whole backup restore.
 */
private fun decodeLinkList(raw: String): List<String> = runCatching {
    Json.decodeFromString(ListSerializer(String.serializer()), raw)
}.getOrDefault(emptyList())

/** Serializes a link list back into the JSON array column representation. */
private fun encodeLinkList(links: List<String>): String =
    Json.encodeToString(ListSerializer(String.serializer()), links)

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
    @Serializable(with = LocalTimeSerializer::class)
    val dueTime: LocalTime? = null,
    val startDate: String? = null,
    @Serializable(with = LocalTimeSerializer::class)
    val startTime: LocalTime? = null,
    val endDate: String? = null,
    @Serializable(with = LocalTimeSerializer::class)
    val endTime: LocalTime? = null,
    val accentColor: Long? = null,
    val emoji: String? = null,
    val completedAt: Long? = null,
    val someday: Boolean = false,
    val archivedAt: Long? = null,
    val isPinned: Boolean = false,
    /** MR-1: denormalized snapshot of depends-on task IDs at backup time. */
    val dependsOn: List<String>? = null,
    val estimateMinutes: Int? = null,
    val recurrenceRule: String? = null,
    /** Wikilink targets of this task, stored as a list in JSON and as a JSON array column in Room. */
    val outgoingLinks: List<String> = emptyList(),
    val aiSuppressedTagIds: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
)

fun TaskEntity.toDto(): TaskDto = TaskDto(
    id = id, title = title, description = description,
    priority = priority.name, kind = kind.name,
    projectId = projectId, dueDate = dueDate, dueTime = dueTime.toLocalTimeOrNull(),
    startDate = startDate, startTime = startTime.toLocalTimeOrNull(),
    endDate = endDate, endTime = endTime.toLocalTimeOrNull(),
    accentColor = accentColor, emoji = emoji,
    completedAt = completedAt, someday = someday,
    archivedAt = archivedAt, isPinned = isPinned,
    estimateMinutes = estimateMinutes,
    recurrenceRule = recurrenceRule,
    outgoingLinks = decodeLinkList(outgoingLinks), // JSON string → List
    aiSuppressedTagIds = decodeLinkList(aiSuppressedTagIds), // JSON string → List
    createdAt = createdAt, updatedAt = updatedAt,
)

fun TaskDto.toEntity(userId: String): TaskEntity = TaskEntity(
    id = id, title = title, description = description,
    priority = com.singularity.todo.feature.tasks.domain.model.TaskPriority.valueOf(priority),
    kind = com.singularity.todo.feature.tasks.domain.model.TaskKind.valueOf(kind),
    projectId = projectId, dueDate = dueDate,
    dueTime = dueTime?.let { LocalTimeFormats.format(it) },
    startDate = startDate, startTime = startTime?.let { LocalTimeFormats.format(it) },
    endDate = endDate, endTime = endTime?.let { LocalTimeFormats.format(it) },
    accentColor = accentColor, emoji = emoji,
    completedAt = completedAt, someday = someday,
    archivedAt = archivedAt, isPinned = isPinned,
    estimateMinutes = estimateMinutes,
    recurrenceRule = recurrenceRule,
    outgoingLinks = encodeLinkList(outgoingLinks), // List → JSON string
    aiSuppressedTagIds = encodeLinkList(aiSuppressedTagIds), // List → JSON string
    createdAt = createdAt, updatedAt = updatedAt,
    userId = userId,
    sync = SyncColumns(),
)

// ─── NoteDto ───────────────────────────────────────────────────────────────────

@Serializable
data class NoteDto(
    val id: String,
    val title: String = "",
    val bodyMarkdown: String? = null,
    val bodyHtml: String? = null,
    val isFolder: Boolean = false,
    val kind: String = "Plain",
    val parentNoteId: String? = null,
    val isPinned: Boolean = false,
    val pinnedAt: Long? = null,
    val color: Int? = null,
    val sortOrder: Int = 0,
    val wordCount: Int = 0,
    val charCount: Int = 0,
    val outgoingLinks: List<String> = emptyList(),
    val taskId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val archivedAt: Long? = null,
)

fun NoteEntity.toDto(): NoteDto = NoteDto(
    id = id, title = title, bodyMarkdown = bodyMarkdown,
    bodyHtml = bodyHtml, isFolder = isFolder,
    kind = kind.name, parentNoteId = parentNoteId,
    isPinned = isPinned,
    pinnedAt = pinnedAt,
    color = color,
    sortOrder = sortOrder,
    wordCount = wordCount,
    charCount = charCount,
    outgoingLinks = decodeLinkList(outgoingLinks), // JSON string → List
    taskId = taskId,
    createdAt = createdAt, updatedAt = updatedAt,
    deletedAt = deletedAt, archivedAt = archivedAt,
)

fun NoteDto.toEntity(userId: String): NoteEntity = NoteEntity(
    id = id, userId = userId, title = title,
    bodyMarkdown = bodyMarkdown, bodyHtml = bodyHtml,
    isFolder = isFolder,
    kind = com.singularity.todo.feature.notes.NoteKind.valueOf(kind),
    parentNoteId = parentNoteId,
    isPinned = isPinned,
    pinnedAt = pinnedAt,
    color = color,
    sortOrder = sortOrder,
    wordCount = wordCount,
    charCount = charCount,
    outgoingLinks = encodeLinkList(outgoingLinks), // List → JSON string
    taskId = taskId,
    createdAt = createdAt, updatedAt = updatedAt,
    deletedAt = deletedAt, archivedAt = archivedAt,
    sync = SyncColumns(),
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
    val idempotencyKey: String? = null,
    val externalId: String? = null,
)

fun ProjectEntity.toDto(): ProjectDto = ProjectDto(
    id = id, name = name, color = color, icon = icon,
    description = description, createdAt = createdAt, updatedAt = updatedAt,
    isDefault = isDefault, dueDate = dueDate, team = team,
    isDeleted = isDeleted, deletedAt = deletedAt, parentId = parentId,
    sortOrder = sortOrder, idempotencyKey = idempotencyKey, externalId = externalId,
)

fun ProjectDto.toEntity(userId: String): ProjectEntity = ProjectEntity(
    id = id, userId = userId, name = name, color = color,
    icon = icon, description = description,
    createdAt = createdAt, updatedAt = updatedAt,
    isDefault = isDefault, dueDate = dueDate, team = team,
    isDeleted = isDeleted, deletedAt = deletedAt, parentId = parentId,
    sortOrder = sortOrder, idempotencyKey = idempotencyKey, externalId = externalId,
    sync = SyncColumns(),
)

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

fun TagEntity.toDto(): TagDto = TagDto(
    id = id,
    name = name,
    color = color,
    createdAt = createdAt,
    updatedAt = updatedAt,
    groupId = groupId,
    sortOrder = sortOrder,
    deletedAt = deletedAt,
)

fun TagDto.toEntity(userId: String): TagEntity = TagEntity(
    id = id, userId = userId, name = name, color = color,
    createdAt = createdAt, updatedAt = updatedAt,
    groupId = groupId, sortOrder = sortOrder, deletedAt = deletedAt,
    sync = SyncColumns(),
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
    val deletedAt: Long? = null,
)

fun AttachmentEntity.toDto(): AttachmentDto = AttachmentDto(
    id = id, taskId = taskId, type = type,
    url = url, title = title, localPath = localPath,
    remoteUrl = remoteUrl, fileSizeBytes = fileSizeBytes,
    mimeType = mimeType, checksum = checksum,
    syncStatus = syncStatus, createdAt = createdAt,
    updatedAt = updatedAt, deletedAt = deletedAt,
)

fun AttachmentDto.toEntity(userId: String): AttachmentEntity = AttachmentEntity(
    id = id, taskId = taskId, userId = userId, type = type,
    url = url, title = title, localPath = localPath,
    remoteUrl = remoteUrl, fileSizeBytes = fileSizeBytes,
    mimeType = mimeType, checksum = checksum,
    syncStatus = syncStatus, createdAt = createdAt,
    updatedAt = updatedAt, deletedAt = deletedAt,
    serverVersion = 0L, hlc = null,
)

// ─── TaskTagDto ───────────────────────────────────────────────────────────────

@Serializable
data class TaskTagDto(val taskId: String, val tagId: String)

fun TaskTagCrossRef.toDto(): TaskTagDto = TaskTagDto(taskId = taskId, tagId = tagId)

fun TaskTagDto.toEntity(): TaskTagCrossRef = TaskTagCrossRef(taskId = taskId, tagId = tagId)

// ─── TaskDependencyDto (MR-1) ─────────────────────────────────────────────────

@Serializable
data class TaskDependencyDto(val taskId: String, val dependsOnTaskId: String)

fun TaskDependencyCrossRef.toDto(): TaskDependencyDto = TaskDependencyDto(
    taskId = taskId,
    dependsOnTaskId = dependsOnTaskId,
)

fun TaskDependencyDto.toEntity(): TaskDependencyCrossRef = TaskDependencyCrossRef(
    taskId = taskId,
    dependsOnTaskId = dependsOnTaskId,
)

// ─── AgendaViewDto (MR-1: agenda_views in backup) ─────────────────────────────

@Serializable
data class AgendaViewDto(
    val id: String,
    val name: String,
    val sectionsJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)

fun AgendaViewEntity.toDto(): AgendaViewDto = AgendaViewDto(
    id = id,
    name = name,
    sectionsJson = sectionsJson,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun AgendaViewDto.toEntity(userId: String): AgendaViewEntity = AgendaViewEntity(
    id = id,
    userId = userId,
    name = name,
    sectionsJson = sectionsJson,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
