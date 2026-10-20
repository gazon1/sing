package com.singularity.todo.feature.notes

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.sync.DocType
import com.singularity.todo.core.sync.Hlc
import com.singularity.todo.core.sync.SyncableEntity
import com.singularity.todo.core.ui.components.OwnedText
import com.singularity.todo.core.ui.components.StaticText
import com.singularity.todo.feature.tasks.domain.model.TaskId
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
 *
 * `@Serializable` is required: [Note.toJson] resolves `serializer<Note>()`, which
 * in turn needs a serializer for every field type. Without it, **every** enqueue
 * of a note throws and notes silently never reach the sync outbox.
 */
@Serializable
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

/**
 * A note.
 *
 * `@Serializable` is load-bearing, not decorative: [toJson] resolves
 * `serializer<Note>()`. Without the annotation the serializer lookup throws at
 * runtime, and notes are **never** written to the sync outbox — a total loss of
 * note sync, not merely a missing field. The KSP-generated serializer is also
 * what makes the Koin/serialization plugin happy.
 *
 * The annotation is not the whole defence, and the annotation is not what makes
 * the failure visible. `SyncEngine.enqueue` wraps the build-and-insert in
 * `runCatchingResult` and 18 call sites discard the `Result`, so the throw was
 * caught and returned to nobody: that is exactly how the missing annotation
 * shipped a total note-sync outage with no error anywhere
 * (`2026-09-27-write-layer-soundness.md`, MR-4). Since REQ-OS-028 `enqueue` logs
 * and reports the failure itself under the `sync.enqueue_failed` issue key, so
 * this class of defect now names itself instead of being silent. Removing the
 * annotation would break sync again — and be visible when it did.
 */
@Serializable
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
    /**
     * Structural note-to-task linkage. When non-null, this note is attached to the
     * identified task and appears in the task's logbook.
     *
     * This field coexists with [outgoingLinks] — the `task://<id>` URL token is still
     * written into [outgoingLinks] for backward compatibility during migration.
     */
    val taskId: TaskId? = null,
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
        /**
         * Counts how many times the note was rewritten from *outside* this editor.
         *
         * The editor owns the content it shows, so a rewrite it did not make — a
         * proposal applied to the note, a sync from another device — reaches the
         * screen only through this counter. A field that sees it change knows the
         * content it holds was replaced rather than typed, and takes the new
         * content instead of treating it as an echo of its own last push.
         *
         * It advances only when the editor re-seeds itself from the note, never
         * when the user types: a keystroke is the editor's own change and needs
         * no announcement.
         */
        val revision: Long = 0,
    ) : EditorState {
        /**
         * The title as the field's source.
         *
         * The editor owns the title, and the note may be rewritten from outside it — a
         * proposal applied to the note, a sync from another device — so the owner
         * counts the replacements and the field reads the count. The type says so: a
         * field that holds this source cannot be wired as if the title were a value
         * nobody else writes.
         */
        val titleSource: OwnedText get() = OwnedText(title, revision)
    }
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

/**
 * List-specific state for notes.
 *
 * What the list *holds*. What the list is *filtered by* lives on [NotesUiState]:
 * the filter, the sort order and the search query describe the view, not the
 * notes, and they have to survive a state that holds no notes at all.
 */
data class NotesListState(
    val pinned: List<Note> = emptyList(),
    val unpinned: List<Note> = emptyList(),
    val templates: List<Note> = emptyList(),
    val dailyNotes: List<Note> = emptyList(),
    val selectedIds: Set<NoteId> = emptySet(),
    val isSelectionMode: Boolean = false,
) {
    val isEmpty: Boolean get() = pinned.isEmpty() && unpinned.isEmpty()
}

/**
 * UI state for the notes list screen.
 *
 * There is no separate `Empty` variant: an empty list is `Content` whose
 * [NotesListState.isEmpty] is true. A dedicated variant lost `filter` / `sortOrder`,
 * so selecting a filter that matched nothing silently reset the filter chip to "All".
 *
 * ## Why the filter, the sort order and the query live on the interface
 *
 * They describe *what the list is filtered by*, not *what the list holds*, and a list
 * that holds nothing is still filtered: a search that matched no note is a different
 * screen from a list that has never had a note in it.
 *
 * That distinction is not cosmetic. The search field is bound to [searchQuery], and the
 * ViewModel reads the query it is given back out of the state: a state that dropped the
 * query would make the screen pass an empty string to the field, the field would hand
 * that empty string back to the ViewModel as if the user had cleared it, and the list
 * would never come back. The same held for the error path — a repository failure that
 * emitted an empty list state silently cleared the query, the chip and the sort order.
 *
 * Every variant declares the three as constructor parameters *without* defaults, so the
 * compiler — not a reviewer — forces every construction site to say what the user had
 * chosen.
 */
sealed interface NotesUiState {
    /** The filter the list is filtered by. */
    val filter: NoteFilter

    /** The order the list is sorted in. */
    val sortOrder: NoteSortOrder

    /** The query the list is filtered by. Empty when nothing is filtered. */
    val searchQuery: String

    /** The list has not arrived yet. Carries the filter, the order and the query so far. */
    data class Loading(
        override val filter: NoteFilter,
        override val sortOrder: NoteSortOrder,
        override val searchQuery: String,
    ) : NotesUiState

    /** The list is here. */
    data class Content(
        val list: NotesListState,
        override val filter: NoteFilter,
        override val sortOrder: NoteSortOrder,
        override val searchQuery: String,
    ) : NotesUiState

    /**
     * The list could not be loaded.
     *
     * The filter, the sort order and the query travel with the failure: the screen
     * still shows the search field and the filter chips, and a state that reset them
     * would clear what the user had chosen.
     */
    data class Error(
        val message: String,
        override val filter: NoteFilter,
        override val sortOrder: NoteSortOrder,
        override val searchQuery: String,
    ) : NotesUiState
}

/**
 * The query as the search field's source.
 *
 * The ViewModel holds the query the user typed and hands it back unchanged — on the error
 * path too — so it never replaces the field's value with one the field never produced: the
 * field's echo guard needs no lifting, and there is no counter to read.
 *
 * It is a *view* of the query rather than a piece of the state the machine carries: every
 * variant already carries the query itself, as a required constructor parameter, and the
 * source is what the field is handed. A property of the interface would say the variants
 * have to carry the source too — and then the query and the source would be two values
 * that could disagree.
 */
val NotesUiState.searchQuerySource: StaticText get() = StaticText(searchQuery)
