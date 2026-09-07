package com.singularity.todo.feature.notes

import com.singularity.todo.core.database.NoteDao
import com.singularity.todo.core.database.NoteEntity
import com.singularity.todo.core.database.toEpochMillis
import com.singularity.todo.core.database.toEpochMillisOrNull
import com.singularity.todo.core.database.toInstant
import com.singularity.todo.core.database.toInstantOrNull
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tasks.UserId
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
    fun watchNote(id: NoteId): Flow<Note?>
    fun searchNotes(query: String): Flow<List<Note>>

    // ─── Editor mutations (whole-Note) ──────────────────────────────────────
    suspend fun create(note: Note): Result<Unit>
    suspend fun update(note: Note): Result<Unit>

    // ─── Editor mutations (id + fields — autosave path) ─────────────────────
    suspend fun createWithContent(userId: UserId, id: NoteId, title: String, bodyMarkdown: String, bodyHtml: String): Result<NoteId>
    suspend fun updateContent(id: NoteId, title: String, bodyMarkdown: String, bodyHtml: String): Result<Unit>

    // ─── Lifecycle ─────────────────────────────────────────────────────────
    suspend fun softDelete(id: NoteId): Result<Unit>
    suspend fun restore(id: NoteId): Result<Unit>
}

/**
 * Room-backed production [NotesRepository].
 */
class RoomNotesRepository(
    private val noteDao: NoteDao,
    private val clock: Clock
) : NotesRepository {

    override fun watchNotes(userId: UserId): Flow<List<Note>> =
        noteDao.watchAll(userId.value).map { list -> list.map { it.toNote() } }

    override fun watchNote(id: NoteId): Flow<Note?> =
        noteDao.watchById(id.value).map { it?.toNote() }

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
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                archivedAt = null,
            )
        )
        id
    }

    override suspend fun updateContent(
        id: NoteId,
        title: String,
        bodyMarkdown: String,
        bodyHtml: String,
    ): Result<Unit> = runCatching {
        noteDao.updateContent(id.value, title, bodyMarkdown, bodyHtml, clock.now().toEpochMilliseconds())
    }

    override suspend fun softDelete(id: NoteId): Result<Unit> = runCatching {
        noteDao.softDelete(id.value, clock.now().toEpochMilliseconds())
    }

    override suspend fun restore(id: NoteId): Result<Unit> = runCatching {
        noteDao.restore(id.value, clock.now().toEpochMilliseconds())
    }
}

private fun NoteEntity.toNote(): Note = Note(
    id = NoteId.fromString(id),
    userId = UserId.fromString(userId),
    title = title,
    bodyMarkdown = bodyMarkdown,
    bodyHtml = bodyHtml,
    isFolder = isFolder,
    parentNoteId = parentNoteId?.let { NoteId.fromString(it) },
    createdAt = createdAt.toInstant(),
    updatedAt = updatedAt.toInstant(),
    deletedAt = deletedAt.toInstantOrNull(),
    archivedAt = archivedAt.toInstantOrNull()
)

fun Note.toEntity(): NoteEntity = NoteEntity(
    id = id.value,
    userId = userId.value,
    title = title,
    bodyMarkdown = bodyMarkdown,
    bodyHtml = bodyHtml,
    isFolder = isFolder,
    parentNoteId = parentNoteId?.value,
    createdAt = createdAt.toEpochMillis(),
    updatedAt = updatedAt.toEpochMillis(),
    deletedAt = deletedAt?.toEpochMillisOrNull(),
    archivedAt = archivedAt?.toEpochMillisOrNull()
)
