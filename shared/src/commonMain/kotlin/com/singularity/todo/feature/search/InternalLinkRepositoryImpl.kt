package com.singularity.todo.feature.search

import com.singularity.todo.core.database.NoteDao
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.toTask
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.toNote
import com.singularity.todo.feature.tasks.domain.model.Task

class InternalLinkRepositoryImpl(
    private val noteDao: NoteDao,
    private val taskDao: TaskDao,
    private val currentUser: com.singularity.todo.feature.profile.ProfileAwareCurrentUser,
) : InternalLinkRepository {

    override suspend fun searchNotes(userId: UserId, query: String): List<Note> {
        if (query.isBlank()) return emptyList()
        return noteDao.searchByTitle(userId.value, query)
            .map { it.toNote() }
    }

    override suspend fun searchTasks(userId: UserId, query: String): List<Task> {
        if (query.isBlank()) return emptyList()
        return taskDao.searchTitles(userId.value, query)
            .map { it.toTask() }
    }

    override suspend fun getBacklinkNotes(noteId: String, userId: UserId): List<Note> =
        noteDao.getBacklinkNotes(noteId, userId.value)
            .map { it.toNote() }
}
