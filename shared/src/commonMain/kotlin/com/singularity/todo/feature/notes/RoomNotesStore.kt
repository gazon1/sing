package com.singularity.todo.feature.notes

import com.singularity.todo.core.database.NoteDao
import com.singularity.todo.core.database.NoteEntity
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Production [NotesStore] backed by Room via [NoteDao].
 * This is the implementation used in the actual application.
 */
class RoomNotesStore(
    private val noteDao: NoteDao,
    private val clock: Clock
) : NotesStore {

    override fun watchAll(userId: UserId): Flow<List<Note>> =
        noteDao.watchAll(userId.value).map { list -> list.map { it.toNote() } }

    override fun watch(id: String): Flow<Note?> =
        noteDao.watchById(id).map { it?.toNote() }

    override suspend fun create(userId: UserId, id: NoteId, title: String, bodyMarkdown: String): String {
        val now = clock.now()
        val entity = NoteEntity(
            id = id.value,
            userId = userId.value,
            title = title,
            bodyMarkdown = bodyMarkdown,
            bodyHtml = null,
            parentNoteId = null,
            createdAt = now.toEpochMilliseconds(),
            updatedAt = now.toEpochMilliseconds(),
            deletedAt = null,
            archivedAt = null
        )
        noteDao.upsert(entity)
        return id.value
    }

    override suspend fun update(id: String, title: String, bodyMarkdown: String) {
        val existing = noteDao.getById(id) ?: return
        noteDao.upsert(
            existing.copy(
                title = title,
                bodyMarkdown = bodyMarkdown,
                updatedAt = clock.now().toEpochMilliseconds()
            )
        )
    }

    override suspend fun softDelete(id: String) {
        val ts = clock.now().toEpochMilliseconds()
        noteDao.softDelete(id, ts)
    }
}

private fun NoteEntity.toNote(): Note = Note(
    id = NoteId.fromString(id),
    userId = UserId.fromString(userId),
    title = title,
    bodyMarkdown = bodyMarkdown,
    bodyHtml = null,
    isFolder = isFolder,
    parentNoteId = parentNoteId?.let { NoteId.fromString(it) },
    createdAt = kotlinx.datetime.Instant.fromEpochMilliseconds(createdAt),
    updatedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(updatedAt),
    deletedAt = deletedAt?.let { kotlinx.datetime.Instant.fromEpochMilliseconds(it) },
    archivedAt = archivedAt?.let { kotlinx.datetime.Instant.fromEpochMilliseconds(it) }
)
