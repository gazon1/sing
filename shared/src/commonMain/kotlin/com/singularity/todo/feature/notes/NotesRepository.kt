package com.singularity.todo.feature.notes

import com.singularity.todo.core.repository.GenericUserScopedRepository
import com.singularity.todo.core.repository.SoftDeletable
import kotlinx.coroutines.flow.Flow

/**
 * Single contract for notes persistence — list, search, editor CRUD, and
 * AI-tool reads. Replaces the previous pair of `NotesRepository` + `NotesStore`.
 *
 * Mutation contract returns [Result] so transport failures (DB errors) are
 * handled uniformly across list, editor, and tool callers.
 */
interface NotesRepository :
    GenericUserScopedRepository<Note, NoteId>,
    SoftDeletable<Note, NoteId> {

    /**
     * Upserts a note from a remote sync event.
     *
     * Does NOT emit [_changes] — caller is responsible for observability.
     * Used exclusively by pull handlers in [com.singularity.todo.core.sync.SyncBootstrapper].
     *
     * @return the upserted note, or throws on persistence failure
     */
    suspend fun upsert(note: Note): Note

    // ─── Domain methods ───────────────────────────────────────────────────────

    /** Pinned non-deleted notes for the current user. */
    fun watchPinned(): Flow<List<Note>>

    /** Archived non-deleted notes for the current user. */
    fun watchArchived(): Flow<List<Note>>

    /** Root notes (no parent) for the current user. */
    fun watchRootNotes(): Flow<List<Note>>

    /** Search notes for the current user. */
    fun search(query: String): Flow<List<Note>>

    /** Creates a note with content (autosave path). Returns the saved note. */
    suspend fun createWithContent(id: NoteId, title: String, bodyMarkdown: String, bodyHtml: String): Result<NoteId>

    /** Creates a note with an initial title (quick-add path). Returns the new id. */
    suspend fun createNoteWithTitle(title: String): Result<NoteId>

    /** Updates title and body content (autosave path). */
    suspend fun updateContent(id: NoteId, title: String, bodyMarkdown: String, bodyHtml: String): Result<Unit>

    /** Archives a note. */
    suspend fun archive(id: NoteId): Result<Unit>

    /** Unarchives a note. */
    suspend fun unarchive(id: NoteId): Result<Unit>

    /** Pins or unpins a note. */
    suspend fun setPinned(id: NoteId, pinned: Boolean): Result<Unit>

    /** Sets note color. */
    suspend fun setColor(id: NoteId, color: NoteColor?): Result<Unit>

    /** Sets sort order. */
    suspend fun setSortOrder(id: NoteId, sortOrder: Int): Result<Unit>

    /** Updates the outgoing links column for a note. Called after each save. */
    suspend fun setOutgoingLinks(id: NoteId, links: List<String>): Result<Unit>

    // ─── Templates ───────────────────────────────────────────────────────────────

    /** Watches all template notes (kind = Template) for the current user. */
    fun watchTemplates(): Flow<List<Note>>

    /** Watches all daily notes within a date range (for calendar navigation). */
    fun watchDailyNotesInRange(from: String, to: String): Flow<List<Note>>

    /** Returns the daily note for [dateKey] (ISO date string), or null if none exists. */
    suspend fun getDailyNote(dateKey: String): Note?

    /**
     * Creates a new note from a template.
     * Copies the template's title, bodyMarkdown, bodyHtml, and color.
     * @return the newly created note's id.
     */
    suspend fun createFromTemplate(templateId: NoteId, targetTitle: String, targetDateKey: String?): Result<NoteId>

    /**
     * Saves a note as a template (changes kind to Template).
     */
    suspend fun saveAsTemplate(id: NoteId): Result<Unit>

    /**
     * Creates a daily note for [dateKey], optionally seeded from [fromTemplateId].
     * If a daily note for that date already exists, returns its id.
     */
    suspend fun getOrCreateDailyNote(dateKey: String, fromTemplateId: NoteId?): Result<NoteId>
}
