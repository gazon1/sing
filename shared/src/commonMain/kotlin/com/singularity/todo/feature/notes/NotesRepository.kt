package com.singularity.todo.feature.notes

import com.singularity.todo.core.database.NoteDao
import com.singularity.todo.core.database.NoteEntity
import com.singularity.todo.core.database.toEpochMillis
import com.singularity.todo.core.database.toEpochMillisOrNull
import com.singularity.todo.core.database.toInstant
import com.singularity.todo.core.database.toInstantOrNull
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single contract for notes persistence — list, search, editor CRUD, and
 * AI-tool reads. Replaces the previous pair of `NotesRepository` + `NotesStore`.
 *
 * Mutation contract returns [Result] so transport failures (DB errors) are
 * handled uniformly across list, editor, and tool callers.
 */
interface NotesRepository {
    // ─── Reads ──────────────────────────────────────────────────────────────
    fun watchNotes(userId: UserId): Flow<List<Note>>
    fun watchPinned(userId: UserId): Flow<List<Note>>
    fun watchArchived(userId: UserId): Flow<List<Note>>
    fun watchRootNotes(userId: UserId): Flow<List<Note>>
    fun watchNote(id: NoteId): Flow<Note?>
    fun searchNotes(query: String): Flow<List<Note>>

    // ─── Editor mutations (whole-Note) ──────────────────────────────────────
    suspend fun create(note: Note): Result<Unit>
    suspend fun update(note: Note): Result<Unit>

    // ─── Editor mutations (id + fields — autosave path) ─────────────────────
    suspend fun createWithContent(
        userId: UserId,
        id: NoteId,
        title: String,
        bodyMarkdown: String,
        bodyHtml: String,
    ): Result<NoteId>

    /** Creates a note with an initial title (quick-add path). Returns the new id. */
    suspend fun createNoteWithTitle(userId: UserId, title: String): Result<NoteId>
    suspend fun updateContent(id: NoteId, title: String, bodyMarkdown: String, bodyHtml: String): Result<Unit>

    // ─── Lifecycle ─────────────────────────────────────────────────────────
    suspend fun softDelete(id: NoteId): Result<Unit>
    suspend fun restore(id: NoteId): Result<Unit>
    suspend fun archive(id: NoteId): Result<Unit>
    suspend fun unarchive(id: NoteId): Result<Unit>

    // ─── Pin / Color / Sort ─────────────────────────────────────────────────
    suspend fun setPinned(id: NoteId, pinned: Boolean): Result<Unit>
    suspend fun setColor(id: NoteId, color: NoteColor?): Result<Unit>
    suspend fun setSortOrder(id: NoteId, sortOrder: Int): Result<Unit>

    // ─── Internal links (wikilinks) ─────────────────────────────────────────

    /** Updates the outgoing links column for a note. Called after each save. */
    suspend fun setOutgoingLinks(id: NoteId, links: List<String>): Result<Unit>
}

/**
 * Room-backed production [NotesRepository].
 */
class RoomNotesRepository(private val noteDao: NoteDao, private val clock: Clock) : NotesRepository {

    override fun watchNotes(userId: UserId): Flow<List<Note>> =
        noteDao.watchAll(userId.value).map { list -> list.map { it.toNote() } }

    override fun watchPinned(userId: UserId): Flow<List<Note>> =
        noteDao.watchPinned(userId.value).map { list -> list.map { it.toNote() } }

    override fun watchArchived(userId: UserId): Flow<List<Note>> =
        noteDao.watchArchived(userId.value).map { list -> list.map { it.toNote() } }

    override fun watchRootNotes(userId: UserId): Flow<List<Note>> =
        noteDao.watchRootNotes(userId.value).map { list -> list.map { it.toNote() } }

    override fun watchNote(id: NoteId): Flow<Note?> = noteDao.watchById(id.value).map { it?.toNote() }

    override fun searchNotes(query: String): Flow<List<Note>> =
        noteDao.search(query).map { list -> list.map { it.toNote() } }

    override suspend fun create(note: Note): Result<Unit> = runCatching {
        noteDao.upsert(note.toEntity())
    }

    override suspend fun update(note: Note): Result<Unit> = runCatching {
        noteDao.upsert(note.toEntity())
    }

    override suspend fun createWithContent(
        userId: UserId,
        id: NoteId,
        title: String,
        bodyMarkdown: String,
        bodyHtml: String,
    ): Result<NoteId> = runCatching {
        val now = clock.now().toEpochMilliseconds()
        noteDao.upsert(
            NoteEntity(
                id = id.value,
                userId = userId.value,
                title = title,
                bodyMarkdown = bodyMarkdown,
                bodyHtml = bodyHtml,
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
        id
    }

    override suspend fun createNoteWithTitle(userId: UserId, title: String): Result<NoteId> = runCatching {
        val id = NoteId(com.singularity.todo.core.ids.nextId())
        val now = clock.now().toEpochMilliseconds()
        noteDao.upsert(
            NoteEntity(
                id = id.value,
                userId = userId.value,
                title = title,
                bodyMarkdown = null,
                bodyHtml = null,
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
        id
    }

    override suspend fun updateContent(
        id: NoteId,
        title: String,
        bodyMarkdown: String,
        bodyHtml: String,
    ): Result<Unit> = runCatching {
        val wordCount = bodyMarkdown.split(Regex("\\s+")).count { it.isNotBlank() }
        noteDao.updateContent(
            id.value,
            title,
            bodyMarkdown,
            bodyHtml,
            wordCount,
            bodyMarkdown.length,
            clock.now().toEpochMilliseconds(),
        )
    }

    override suspend fun softDelete(id: NoteId): Result<Unit> = runCatching {
        noteDao.softDelete(id.value, clock.now().toEpochMilliseconds())
    }

    override suspend fun restore(id: NoteId): Result<Unit> = runCatching {
        noteDao.restore(id.value, clock.now().toEpochMilliseconds())
    }

    override suspend fun archive(id: NoteId): Result<Unit> = runCatching {
        noteDao.archive(id.value, clock.now().toEpochMilliseconds())
    }

    override suspend fun unarchive(id: NoteId): Result<Unit> = runCatching {
        noteDao.unarchive(id.value, clock.now().toEpochMilliseconds())
    }

    override suspend fun setPinned(id: NoteId, pinned: Boolean): Result<Unit> = runCatching {
        val now = clock.now().toEpochMilliseconds()
        noteDao.setPinned(id.value, pinned, if (pinned) now else null, now)
    }

    override suspend fun setColor(id: NoteId, color: NoteColor?): Result<Unit> = runCatching {
        noteDao.setColor(id.value, color?.value, clock.now().toEpochMilliseconds())
    }

    override suspend fun setSortOrder(id: NoteId, sortOrder: Int): Result<Unit> = runCatching {
        noteDao.setSortOrder(id.value, sortOrder, clock.now().toEpochMilliseconds())
    }

    override suspend fun setOutgoingLinks(id: NoteId, links: List<String>): Result<Unit> = runCatching {
        noteDao.setOutgoingLinks(id.value, links.toLinksJson(), clock.now().toEpochMilliseconds())
    }
}

internal fun NoteEntity.toNote(): Note = Note(
    id = NoteId.fromString(id),
    userId = UserId.fromString(userId),
    title = title,
    bodyMarkdown = bodyMarkdown,
    bodyHtml = bodyHtml,
    isFolder = isFolder,
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
)

fun Note.toEntity(): NoteEntity = NoteEntity(
    id = id.value,
    userId = userId.value,
    title = title,
    bodyMarkdown = bodyMarkdown,
    bodyHtml = bodyHtml,
    isFolder = isFolder,
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

private fun List<String>.toLinksJson(): String = if (isEmpty()) {
    "[]"
} else {
    buildString {
    append('[')
    forEachIndexed { index, link ->
        if (index > 0) append(',')
        append('"').append(link).append('"')
    }
    append(']')
}
}
