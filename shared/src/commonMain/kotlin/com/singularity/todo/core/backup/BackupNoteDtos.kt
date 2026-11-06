package com.singularity.todo.core.backup

import com.singularity.todo.core.database.NoteEntity
import com.singularity.todo.core.database.SyncColumns
import com.singularity.todo.core.serialization.parseWireName
import com.singularity.todo.feature.notes.NoteKind
import kotlinx.serialization.Serializable

// ─── NoteDto ───────────────────────────────────────────────────────────────────

/**
 * The note rows of a payload.
 */

@Serializable
data class NoteDto(
    val id: String,
    val title: String = "",
    val bodyMarkdown: String? = null,
    val bodyHtml: String? = null,
    val isFolder: Boolean = false,
    /**
     * The kind of note, under the name the format gives it: `plain`, `daily` or
     * `template`.
     *
     * The name is a contract of the format, not the name of the Kotlin constant —
     * see [com.singularity.todo.core.serialization.WireNamed]. A payload written
     * before the format named its values (`Plain`) is still read.
     */
    val kind: String = "plain",
    val parentNoteId: String? = null,
    val isPinned: Boolean = false,
    val pinnedAt: Long? = null,
    val color: Int? = null,
    val sortOrder: Int = 0,
    val wordCount: Int = 0,
    val charCount: Int = 0,
    val outgoingLinks: List<String> = emptyList(),
    val taskId: String? = null,
    val projectId: String? = null,
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
        kind = kind.wireName,
        parentNoteId = parentNoteId,
        isPinned = isPinned,
        pinnedAt = pinnedAt,
        color = color,
        sortOrder = sortOrder,
        wordCount = wordCount,
        charCount = charCount,
        outgoingLinks = decodeLinkList(outgoingLinks), // JSON string → List
        taskId = taskId,
        projectId = projectId,
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
        kind = parseWireName<NoteKind>(kind),
        parentNoteId = parentNoteId,
        isPinned = isPinned,
        pinnedAt = pinnedAt,
        color = color,
        sortOrder = sortOrder,
        wordCount = wordCount,
        charCount = charCount,
        outgoingLinks = encodeLinkList(outgoingLinks), // List → JSON string
        taskId = taskId,
        projectId = projectId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        archivedAt = archivedAt,
        sync = SyncColumns(),
    )
