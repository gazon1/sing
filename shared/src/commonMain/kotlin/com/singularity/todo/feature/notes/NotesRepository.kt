package com.singularity.todo.feature.notes

import com.singularity.todo.core.database.NoteDao
import com.singularity.todo.core.database.NoteEntity
import com.singularity.todo.core.database.SyncColumns
import com.singularity.todo.core.database.toEpochMillis
import com.singularity.todo.core.database.toEpochMillisOrNull
import com.singularity.todo.core.database.toInstant
import com.singularity.todo.core.database.toInstantOrNull
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.repository.GenericUserScopedRepository
import com.singularity.todo.core.repository.SoftDeletable
import com.singularity.todo.core.repository.assertCanWrite
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.core.sync.Hlc
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

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

/**
 * Room-backed production [NotesRepository].
 */
class RoomNotesRepository(
    private val noteDao: NoteDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
    private val syncRepository: com.singularity.todo.core.sync.SyncRepository,
) : NotesRepository {

    // ─── GenericUserScopedRepository ───────────────────────────────────────────

    override fun observeAll(): Flow<List<Note>> = currentUser.observeForCurrentUser { uid ->
        noteDao.watchAll(uid.value).map { list -> list.map { it.toNote() } }
    }

    override fun observe(id: NoteId): Flow<Note?> = currentUser.observeForCurrentUser { uid ->
        noteDao.watchByIdForUser(id.value, uid.value).map { it?.toNote() }
    }

    override suspend fun get(id: NoteId): Note? {
        val uid = currentUser.scopedUserId.value
        return noteDao.getByIdForUser(id.value, uid.value)?.toNote()
    }

    override suspend fun create(item: Note): Result<Note> = runCatching {
        currentUser.assertCanWrite(entityId = item.syncId, entityUserId = item.userId)
        noteDao.upsert(item.toEntity())
        item.also { syncRepository.enqueue(it) }
    }

    override suspend fun update(item: Note): Result<Note> = runCatching {
        currentUser.assertCanWrite(entityId = item.syncId, entityUserId = item.userId)
        // Re-stamp after the guard, as Tasks now does: the guard has established
        // that userId is current-or-anonymous, so normalising cannot lose
        // information, whereas upserting a caller's anonymous id verbatim would
        // orphan the row.
        val toUpdate = item.copy(userId = currentUser.scopedUserId.value)
        noteDao.upsert(toUpdate.toEntity())
        toUpdate.also { syncRepository.enqueue(it) }
    }

    /**
     * Re-reads [id] and pushes that state to the sync outbox.
     *
     * Every narrow method below writes through a targeted `UPDATE` / a hand-built
     * `NoteEntity` rather than a domain `update`, so the caller's note is stale by
     * the time the write lands. Re-reading is what makes the pushed payload match
     * the database — including for deletes, which propagate as state (`deletedAt`)
     * rather than as a tombstone, since `buildPatch` already ships the full
     * snapshot. `outgoingLinks` in particular is a serialised field of [Note] and
     * must travel with the rest of the state.
     */
    private suspend fun enqueueFresh(id: NoteId) {
        val row = noteDao.getByIdForUser(id.value, currentUser.scopedUserId.value.value) ?: return
        syncRepository.enqueue(row.toNote())
    }

    // ─── Remote apply (pull handler) ────────────────────────────────────────────

    override suspend fun upsert(note: Note): Note {
        noteDao.upsert(note.toEntity())
        return note
    }

    override suspend fun delete(id: NoteId): Result<Unit> = runCatching {
        val rows = noteDao.softDeleteForUser(
            id.value,
            clock.now().toEpochMilliseconds(),
            currentUser.scopedUserId.value.value,
        )
        require(rows > 0) { "Note $id not found or not owned by current user" }
        enqueueFresh(id)
    }

    // ─── SoftDeletable ────────────────────────────────────────────────────────

    override suspend fun restore(id: NoteId): Result<Unit> = runCatching {
        val rows = noteDao.restoreForUser(
            id.value,
            clock.now().toEpochMilliseconds(),
            currentUser.scopedUserId.value.value,
        )
        require(rows > 0) { "Note $id not found or not owned by current user" }
        enqueueFresh(id)
    }

    // ─── Domain methods ───────────────────────────────────────────────────────

    override fun watchPinned(): Flow<List<Note>> = currentUser.observeForCurrentUser { uid ->
        noteDao.watchPinned(uid.value).map { list -> list.map { it.toNote() } }
    }

    override fun watchArchived(): Flow<List<Note>> = currentUser.observeForCurrentUser { uid ->
        noteDao.watchArchived(uid.value).map { list -> list.map { it.toNote() } }
    }

    override fun watchRootNotes(): Flow<List<Note>> = currentUser.observeForCurrentUser { uid ->
        noteDao.watchRootNotes(uid.value).map { list -> list.map { it.toNote() } }
    }

    override fun search(query: String): Flow<List<Note>> = currentUser.observeForCurrentUser { uid ->
        noteDao.watchSearchByTitle(uid.value, query).map { list -> list.map { it.toNote() } }
    }

    override suspend fun createWithContent(
        id: NoteId,
        title: String,
        bodyMarkdown: String,
        bodyHtml: String,
    ): Result<NoteId> = runCatching {
        val uid = currentUser.scopedUserId.value
        val now = clock.now().toEpochMilliseconds()
        noteDao.upsert(
            NoteEntity(
                id = id.value,
                userId = uid.value,
                title = title,
                bodyMarkdown = bodyMarkdown,
                bodyHtml = bodyHtml,
                kind = NoteKind.Plain,
                parentNoteId = null,
                isPinned = false,
                pinnedAt = null,
                color = null,
                sortOrder = 0,
                wordCount = bodyMarkdown.split(Regex("\\s+")).count { it.isNotBlank() },
                charCount = bodyMarkdown.length,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                archivedAt = null,
            ),
        )
        enqueueFresh(id)
        id
    }

    override suspend fun createNoteWithTitle(title: String): Result<NoteId> = runCatching {
        val uid = currentUser.scopedUserId.value
        val id = NoteId(com.singularity.todo.core.ids.nextId())
        val now = clock.now().toEpochMilliseconds()
        noteDao.upsert(
            NoteEntity(
                id = id.value,
                userId = uid.value,
                title = title,
                bodyMarkdown = null,
                bodyHtml = null,
                kind = NoteKind.Plain,
                parentNoteId = null,
                isPinned = false,
                pinnedAt = null,
                color = null,
                sortOrder = 0,
                wordCount = 0,
                charCount = 0,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                archivedAt = null,
            ),
        )
        enqueueFresh(id)
        id
    }

    override suspend fun updateContent(
        id: NoteId,
        title: String,
        bodyMarkdown: String,
        bodyHtml: String,
    ): Result<Unit> = runCatching {
        val wordCount = bodyMarkdown.split(Regex("\\s+")).count { it.isNotBlank() }
        val rows = noteDao.updateContentForUser(
            id = id.value,
            title = title,
            markdown = bodyMarkdown,
            html = bodyHtml,
            wordCount = wordCount,
            charCount = bodyMarkdown.length,
            updatedAt = clock.now().toEpochMilliseconds(),
            userId = currentUser.scopedUserId.value.value,
        )
        require(rows > 0) { "Note $id not found or not owned by current user" }
        enqueueFresh(id)
    }

    override suspend fun archive(id: NoteId): Result<Unit> = runCatching {
        val rows = noteDao.archiveForUser(
            id.value,
            clock.now().toEpochMilliseconds(),
            currentUser.scopedUserId.value.value,
        )
        require(rows > 0) { "Note $id not found or not owned by current user" }
        enqueueFresh(id)
    }

    override suspend fun unarchive(id: NoteId): Result<Unit> = runCatching {
        val rows = noteDao.unarchiveForUser(
            id.value,
            clock.now().toEpochMilliseconds(),
            currentUser.scopedUserId.value.value,
        )
        require(rows > 0) { "Note $id not found or not owned by current user" }
        enqueueFresh(id)
    }

    override suspend fun setPinned(id: NoteId, pinned: Boolean): Result<Unit> = runCatching {
        val now = clock.now().toEpochMilliseconds()
        val rows = noteDao.setPinnedForUser(
            id = id.value,
            pinned = pinned,
            pinnedAt = if (pinned) now else null,
            ts = now,
            userId = currentUser.scopedUserId.value.value,
        )
        require(rows > 0) { "Note $id not found or not owned by current user" }
        enqueueFresh(id)
    }

    override suspend fun setColor(id: NoteId, color: NoteColor?): Result<Unit> = runCatching {
        val rows = noteDao.setColorForUser(
            id = id.value,
            color = color?.value,
            ts = clock.now().toEpochMilliseconds(),
            userId = currentUser.scopedUserId.value.value,
        )
        require(rows > 0) { "Note $id not found or not owned by current user" }
        enqueueFresh(id)
    }

    override suspend fun setSortOrder(id: NoteId, sortOrder: Int): Result<Unit> = runCatching {
        val rows = noteDao.setSortOrderForUser(
            id = id.value,
            sortOrder = sortOrder,
            ts = clock.now().toEpochMilliseconds(),
            userId = currentUser.scopedUserId.value.value,
        )
        require(rows > 0) { "Note $id not found or not owned by current user" }
        enqueueFresh(id)
    }

    override suspend fun setOutgoingLinks(id: NoteId, links: List<String>): Result<Unit> = runCatching {
        val rows = noteDao.setOutgoingLinksForUser(
            id = id.value,
            linksJson = links.toLinksJson(),
            updatedAt = clock.now().toEpochMilliseconds(),
            userId = currentUser.scopedUserId.value.value,
        )
        require(rows > 0) { "Note $id not found or not owned by current user" }
        enqueueFresh(id)
    }

    // ─── Templates and daily notes ────────────────────────────────────────────────

    override fun watchTemplates(): Flow<List<Note>> = currentUser.observeForCurrentUser { uid ->
        noteDao.watchTemplates(uid.value).map { list -> list.map { it.toNote() } }
    }

    override fun watchDailyNotesInRange(from: String, to: String): Flow<List<Note>> =
        currentUser.observeForCurrentUser { uid ->
            noteDao.watchDailyNotesInRange(uid.value, from, to).map { list -> list.map { it.toNote() } }
        }

    override suspend fun getDailyNote(dateKey: String): Note? {
        val uid = currentUser.scopedUserId.value
        return noteDao.getDailyNote(uid.value, dateKey)?.toNote()
    }

    override suspend fun createFromTemplate(
        templateId: NoteId,
        targetTitle: String,
        targetDateKey: String?,
    ): Result<NoteId> = runCatching {
        val uid = currentUser.scopedUserId.value
        val template = noteDao.getByIdForUser(templateId.value, uid.value)
            ?: throw IllegalArgumentException("Template not found: $templateId")
        val now = clock.now().toEpochMilliseconds()
        val newId = NoteId(com.singularity.todo.core.ids.nextId())
        val finalTitle = targetDateKey?.let { "$it — $targetTitle" } ?: targetTitle
        noteDao.upsert(
            NoteEntity(
                id = newId.value,
                userId = uid.value,
                title = finalTitle,
                bodyMarkdown = template.bodyMarkdown,
                bodyHtml = template.bodyHtml,
                isFolder = false,
                kind = if (targetDateKey != null) NoteKind.Daily else NoteKind.Plain,
                parentNoteId = null,
                isPinned = false,
                pinnedAt = null,
                color = template.color,
                sortOrder = 0,
                wordCount = template.bodyMarkdown?.split(Regex("\\s+"))?.count { it.isNotBlank() } ?: 0,
                charCount = template.bodyMarkdown?.length ?: 0,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                archivedAt = null,
            ),
        )
        enqueueFresh(newId)
        newId
    }

    override suspend fun saveAsTemplate(id: NoteId): Result<Unit> = runCatching {
        val rows = noteDao.setKindForUser(
            id = id.value,
            kind = NoteKind.Template.name,
            ts = clock.now().toEpochMilliseconds(),
            userId = currentUser.scopedUserId.value.value,
        )
        require(rows > 0) { "Note $id not found or not owned by current user" }
        enqueueFresh(id)
    }

    override suspend fun getOrCreateDailyNote(dateKey: String, fromTemplateId: NoteId?): Result<NoteId> = runCatching {
        val uid = currentUser.scopedUserId.value
        val existing = noteDao.getDailyNote(uid.value, dateKey)
        if (existing != null) {
            return@runCatching NoteId.fromString(existing.id)
        }
        val now = clock.now().toEpochMilliseconds()
        val newId = NoteId(com.singularity.todo.core.ids.nextId())
        val template = fromTemplateId?.let { noteDao.getByIdForUser(it.value, uid.value) }
        noteDao.upsert(
            NoteEntity(
                id = newId.value,
                userId = uid.value,
                title = dateKey,
                bodyMarkdown = template?.bodyMarkdown,
                bodyHtml = template?.bodyHtml,
                isFolder = false,
                kind = NoteKind.Daily,
                parentNoteId = null,
                isPinned = false,
                pinnedAt = null,
                color = template?.color,
                sortOrder = 0,
                wordCount = template?.bodyMarkdown?.split(Regex("\\s+"))?.count { it.isNotBlank() } ?: 0,
                charCount = template?.bodyMarkdown?.length ?: 0,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                archivedAt = null,
            ),
        )
        // Only the creating path enqueues — the early return above found an
        // existing note and changed nothing.
        enqueueFresh(newId)
        newId
    }
}

internal fun NoteEntity.toNote(): Note = Note(
    id = NoteId.fromString(id),
    userId = UserId.fromString(userId),
    title = title,
    bodyMarkdown = bodyMarkdown,
    bodyHtml = bodyHtml,
    isFolder = isFolder,
    kind = kind,
    parentNoteId = parentNoteId?.let { NoteId.fromString(it) },
    isPinned = isPinned,
    pinnedAt = pinnedAt.toInstantOrNull(),
    color = if (color != null && color != 0) NoteColor(color) else null,
    sortOrder = sortOrder,
    wordCount = wordCount,
    charCount = charCount,
    outgoingLinks = outgoingLinks.parseLinksJson(),
    createdAt = createdAt.toInstant(),
    updatedAt = updatedAt.toInstant(),
    deletedAt = deletedAt.toInstantOrNull(),
    archivedAt = archivedAt.toInstantOrNull(),
    serverVersion = sync.serverVersion,
    hlc = sync.hlc?.let { Hlc(it) },
)

fun Note.toEntity(): NoteEntity = NoteEntity(
    id = id.value,
    userId = userId.value,
    title = title,
    bodyMarkdown = bodyMarkdown,
    bodyHtml = bodyHtml,
    isFolder = isFolder,
    kind = kind,
    parentNoteId = parentNoteId?.value,
    isPinned = isPinned,
    pinnedAt = pinnedAt?.toEpochMillis(),
    color = color?.value,
    sortOrder = sortOrder,
    wordCount = wordCount,
    charCount = charCount,
    outgoingLinks = outgoingLinks.toLinksJson(),
    createdAt = createdAt.toEpochMillis(),
    updatedAt = updatedAt.toEpochMillis(),
    deletedAt = deletedAt?.toEpochMillisOrNull(),
    archivedAt = archivedAt?.toEpochMillisOrNull(),
    sync = SyncColumns(serverVersion = serverVersion, hlc = hlc?.encoded),
)

private fun String.parseLinksJson(): List<String> {
    if (isBlank() || this == "[]") return emptyList()
    val result = mutableListOf<String>()
    val regex = Regex(""""([^"\\]+)"""")
    for (match in regex.findAll(this)) {
        result.add(match.groupValues[1])
    }
    return result
}

private fun List<String>.toLinksJson(): String {
    if (isEmpty()) return "[]"
    // `items` is bound explicitly on purpose. Inside `buildString` the implicit
    // receiver is the StringBuilder, which is a CharSequence, so a bare
    // `forEachIndexed` resolves to CharSequence.forEachIndexed and iterates over
    // the builder's own characters *while appending to it* — an unbounded loop
    // that ends in OutOfMemoryError. Binding the list removes the ambiguity.
    // The identical bug lived in TaskOutgoingLinks.toLinksJson.
    val items = this
    return buildString {
        append('[')
        items.forEachIndexed { index, link ->
            if (index > 0) append(',')
            append('"').append(link).append('"')
        }
        append(']')
    }
}
