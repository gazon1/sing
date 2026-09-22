package com.singularity.todo.feature.notes

import com.singularity.todo.core.ids.UserId
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
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
        val Blue = NoteColor(0xFFBBDEFB.toInt())
        val Green = NoteColor(0xFFC8E6C9.toInt())
        val Red = NoteColor(0xFFFFCDD2.toInt())
        val Purple = NoteColor(0xFFE1BEE7.toInt())
        val Orange = NoteColor(0xFFFFE0B2.toInt())
        val Grey = NoteColor(0xFFCFD8DC.toInt())
        val None = NoteColor(0)
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
    val userId: UserId,
)

// ─── Editor state ───────────────────────────────────────────────────────────

/** UI state for the note editor screen. */
sealed interface EditorState {
    data object Empty : EditorState
    data class Editing(
        val id: String,
        val title: String,
        val html: String,
        val isDirty: Boolean = false,
        /** True until the note is persisted for the first time (create-on-first-save). */
        val isNew: Boolean = false,
    ) : EditorState
}

/** Result of an AI note improvement action. */
sealed interface NoteAiResult {
    data class Improved(val title: String, val body: String) : NoteAiResult
    data class Error(val message: String) : NoteAiResult
}

// ─── List state ────────────────────────────────────────────────────────────

/** Filter for the notes list. */
enum class NoteFilter {
    All,
    Pinned,
    Archived,
}

/** Sort order for the notes list. */
enum class NoteSortOrder {
    UpdatedDesc,
    UpdatedAsc,
    TitleAsc,
    TitleDesc,
}

/** List-specific state for notes. */
data class NotesListState(
    val pinned: List<Note> = emptyList(),
    val unpinned: List<Note> = emptyList(),
    val filter: NoteFilter = NoteFilter.All,
    val sortOrder: NoteSortOrder = NoteSortOrder.UpdatedDesc,
    val selectedIds: Set<NoteId> = emptySet(),
    val isSelectionMode: Boolean = false,
) {
    val isEmpty: Boolean get() = pinned.isEmpty() && unpinned.isEmpty()
}

/** UI state for the notes list screen. */
sealed interface NotesUiState {
    data object Loading : NotesUiState
    data object Empty : NotesUiState
    data class Content(val list: NotesListState) : NotesUiState
    data class Error(val message: String) : NotesUiState
}
