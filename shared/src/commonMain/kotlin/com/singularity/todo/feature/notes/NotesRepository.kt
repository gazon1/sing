package com.singularity.todo.feature.notes

import com.singularity.todo.core.database.NoteDao
import com.singularity.todo.core.database.NoteEntity
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class NotesRepository(
    private val noteDao: NoteDao,
    private val clock: Clock
) {
    fun watchNotes(userId: UserId): Flow<List<Note>> {
        return noteDao.watchAll(userId.value).map { list -> list.map { it.toNote() } }
    }

    fun watchNote(id: NoteId): Flow<Note?> {
        return noteDao.watchById(id.value).map { it?.toNote() }
    }

    fun searchNotes(query: String): Flow<List<Note>> {
        return noteDao.search(query).map { list -> list.map { it.toNote() } }
    }

    suspend fun create(note: Note): Result<Unit> = runCatching {
        noteDao.upsert(note.toEntity())
    }

    suspend fun update(note: Note): Result<Unit> = runCatching {
        noteDao.upsert(note.toEntity())
    }

    suspend fun softDelete(id: NoteId): Result<Unit> = runCatching {
        val ts = clock.now().toEpochMilliseconds()
        noteDao.softDelete(id.value, ts)
    }

    suspend fun restore(id: NoteId): Result<Unit> = runCatching {
        val ts = clock.now().toEpochMilliseconds()
        noteDao.restore(id.value, ts)
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
    createdAt = kotlinx.datetime.Instant.fromEpochMilliseconds(createdAt),
    updatedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(updatedAt),
    deletedAt = deletedAt?.let { kotlinx.datetime.Instant.fromEpochMilliseconds(it) },
    archivedAt = archivedAt?.let { kotlinx.datetime.Instant.fromEpochMilliseconds(it) }
)

fun Note.toEntity(): NoteEntity = NoteEntity(
    id = id.value,
    userId = userId.value,
    title = title,
    bodyMarkdown = bodyMarkdown,
    bodyHtml = bodyHtml,
    isFolder = isFolder,
    parentNoteId = parentNoteId?.value,
    createdAt = createdAt.toEpochMilliseconds(),
    updatedAt = updatedAt.toEpochMilliseconds(),
    deletedAt = deletedAt?.toEpochMilliseconds(),
    archivedAt = archivedAt?.toEpochMilliseconds()
)
