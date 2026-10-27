package com.singularity.todo.core.backup

import com.singularity.todo.core.database.NoteEntity
import com.singularity.todo.core.database.SyncColumns
import kotlinx.serialization.Serializable

/**
 * The note rows of a payload.
 */
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

fun NoteEntity.toDto(): NoteDto =
    NoteDto(
        id = id,
        title = title,
        bodyMarkdown = bodyMarkdown,
        bodyHtml = bodyHtml,
        isFolder = isFolder,
        kind = kind.name,
        parentNoteId = parentNoteId,
        isPinned = isPinned,
        pinnedAt = pinnedAt,
        color = color,
        sortOrder = sortOrder,
        wordCount = wordCount,
        charCount = charCount,
        outgoingLinks = decodeLinkList(outgoingLinks), // JSON string → List
        taskId = taskId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        archivedAt = archivedAt,
    )

fun NoteDto.toEntity(userId: String): NoteEntity =
    NoteEntity(
        id = id,
        userId = userId,
        title = title,
        bodyMarkdown = bodyMarkdown,
        bodyHtml = bodyHtml,
        isFolder = isFolder,
        kind =
            com.singularity.todo.feature.notes.NoteKind
                .valueOf(kind),
        parentNoteId = parentNoteId,
        isPinned = isPinned,
        pinnedAt = pinnedAt,
        color = color,
        sortOrder = sortOrder,
        wordCount = wordCount,
        charCount = charCount,
        outgoingLinks = encodeLinkList(outgoingLinks), // List → JSON string
        taskId = taskId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        archivedAt = archivedAt,
        sync = SyncColumns(),
    )
