package com.singularity.todo.feature.notes

import com.singularity.todo.feature.tasks.UserId
import java.util.UUID
import kotlin.time.Instant

@JvmInline
value class NoteId(val value: String) {
    companion object {
        fun generate() = NoteId(UUID.randomUUID().toString())
        fun fromString(value: String) = NoteId(value)
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
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    val archivedAt: Instant? = null
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
