package com.singularity.todo.feature.notes

import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/**
 * Abstraction over note persistence — enables mock-free testing via [FakeNotesStore].
 *
 * Unlike [NotesRepository], this interface is designed for the editor use-case:
 * - [watchAll] returns the list of notes for a user (list screen)
 * - [watch] returns a single note by id (editor screen)
 * - [create] / [update] / [softDelete] are the mutation operations
 *
 * All return values are domain models, never entities.
 */
interface NotesStore {
    fun watchAll(userId: UserId): Flow<List<Note>>
    fun watch(id: String): Flow<Note?>
    suspend fun create(userId: UserId, id: NoteId, title: String, bodyMarkdown: String): String
    suspend fun update(id: String, title: String, bodyMarkdown: String)
    suspend fun softDelete(id: String)
}

/** Returns the current instant using the platform clock */
private fun currentInstant(): Instant = Clock.now()

/**
 * In-memory fake for unit tests — no mocking framework required.
 */
class FakeNotesStore : NotesStore {
    private val notes = mutableMapOf<String, Note>()

    override fun watchAll(userId: UserId): Flow<List<Note>> {
        val all = notes.values.filter { it.userId == userId && !it.isDeleted }
        return kotlinx.coroutines.flow.flowOf(all)
    }

    override fun watch(id: String): Flow<Note?> =
        kotlinx.coroutines.flow.flowOf(notes[id])

    override suspend fun create(userId: UserId, id: NoteId, title: String, bodyMarkdown: String): String {
        val ts = currentInstant()
        notes[id.value] = Note(
            id = id,
            userId = userId,
            title = title,
            bodyMarkdown = bodyMarkdown,
            createdAt = ts,
            updatedAt = ts
        )
        return id.value
    }

    override suspend fun update(id: String, title: String, bodyMarkdown: String) {
        notes[id]?.let { existing ->
            notes[id] = existing.copy(
                title = title,
                bodyMarkdown = bodyMarkdown,
                updatedAt = currentInstant()
            )
        }
    }

    override suspend fun softDelete(id: String) {
        notes[id]?.let { existing ->
            notes[id] = existing.copy(deletedAt = currentInstant())
        }
    }

    fun seed(id: String, note: Note) {
        notes[id] = note
    }
}
