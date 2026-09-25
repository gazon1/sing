package com.singularity.todo.feature.notes

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.sync.DocType
import com.singularity.todo.core.sync.Hlc
import com.singularity.todo.core.sync.SyncableEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
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

/**
 * Discriminator for note type.
 *
 * - [Plain]: regular user notes
 * - [Daily]: one note per day, keyed by date (journal/daily-log)
 * - [Template]: note used as a template for creating other notes
 */
@Serializable
enum class NoteKind {
    /** Regular note. */
    Plain,

    /** Daily journal note, one per day. */
    Daily,

    /** Template note used to create new notes from. */
    Template,
}

data class Note(
    val id: NoteId,
    val userId: UserId,
    val title: String = "",
    val bodyMarkdown: String? = null,
    val bodyHtml: String? = null,
    val isFolder: Boolean = false,
    /** Note type: Plain, Daily, or Template. */
    val kind: NoteKind = NoteKind.Plain,
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
    // ─── Sync fields ───────────────────────────────────────────────────────────
    val serverVersion: Long = 0,
    val hlc: Hlc? = null,
) : SyncableEntity {
    val isLeaf: Boolean get() = !isFolder
    val isDeleted: Boolean get() = deletedAt != null
    val isArchived: Boolean get() = archivedAt != null

    // SyncableEntity implementation
    override val syncId: String get() = id.value
    override val docType: DocType get() = DocType.Note
    override val syncServerVersion: Long get() = serverVersion
    override val syncHlc: Hlc? get() = hlc

    override fun toJson(): JsonObject {
        @Suppress("UNCHECKED_CAST")
        val ser = serializer<Note>()
        return StableJson.encodeToJsonElement(ser, this) as JsonObject
    }
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

/** AI actions available in the note editor. */
enum class NoteAiAction {
    Improve,
    Summarize,
    ExtractActions,
    RewriteOneLiner,
    RewriteTldr,
    RewriteStructured,
    SuggestTags,
}

/** Result of a summarize action. */
sealed interface SummarizeResult {
    data class Ok(val summary: String) : SummarizeResult
    data class Error(val message: String) : SummarizeResult
}

/** Result of an extract-actions action. */
sealed interface ExtractActionsResult {
    data class Ok(val actions: List<String>) : ExtractActionsResult
    data class Error(val message: String) : ExtractActionsResult
}

/** Result of a suggest-tags action. */
sealed interface SuggestTagsResult {
    data class Ok(val tags: List<String>) : SuggestTagsResult
    data class Error(val message: String) : SuggestTagsResult
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
    val templates: List<Note> = emptyList(),
    val dailyNotes: List<Note> = emptyList(),
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
