package com.singularity.todo.feature.notes

import com.singularity.todo.core.ids.UserId
import kotlin.time.Instant

@JvmInline
value class NoteId(val value: String) {
    companion object {
        fun generate() = NoteId(com.singularity.todo.core.ids.nextId())
        fun fromString(value: String) = NoteId(value)
    }
}

/**
 * User-facing color for a note. Stored as ARGB Int.
 * Presets match common note-app colors (yellow, blue, green, red, purple).
 */
@JvmInline
value class NoteColor(val value: Int) {
    companion object {
        val Yellow = NoteColor(0xFFFFF9C4.toInt())
        val Blue   = NoteColor(0xFFBBDEFB.toInt())
        val Green  = NoteColor(0xFFC8E6C9.toInt())
        val Red    = NoteColor(0xFFFFCDD2.toInt())
        val Purple = NoteColor(0xFFE1BEE7.toInt())
        val Orange = NoteColor(0xFFFFE0B2.toInt())
        val Grey   = NoteColor(0xFFCFD8DC.toInt())
        val None   = NoteColor(0)
    }
}

data class Note(
    val id: NoteId,
    val userId: UserId,
    val title: String = "",
    val bodyMarkdown: String? = null,
    val bodyHtml: String? = null,
    val isFolder: Boolean = false,
    val parentNoteId: NoteId? = null,
    val isPinned: Boolean = false,
    val pinnedAt: Instant? = null,
    val color: NoteColor? = null,
    val sortOrder: Int = 0,
    val wordCount: Int = 0,
    val charCount: Int = 0,
    val outgoingLinks: List<String> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    val archivedAt: Instant? = null,
) {
    val isLeaf: Boolean get() = !isFolder
    val isDeleted: Boolean get() = deletedAt != null
    val isArchived: Boolean get() = archivedAt != null
}

data class CreateNoteInput(
    val title: String = "",
    val bodyMarkdown: String? = null,
    val isFolder: Boolean = false,
    val parentNoteId: NoteId? = null,
    val userId: UserId
)
