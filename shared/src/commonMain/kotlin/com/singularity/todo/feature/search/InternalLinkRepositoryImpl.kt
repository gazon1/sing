package com.singularity.todo.feature.search

import com.singularity.todo.core.database.NoteDao
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.toNote
import com.singularity.todo.feature.tasks.Task
import com.singularity.todo.feature.tasks.TaskId
import com.singularity.todo.feature.tasks.UserId
import com.singularity.todo.feature.tasks.toTask

class InternalLinkRepositoryImpl(
    private val noteDao: NoteDao,
    private val taskDao: TaskDao,
) : InternalLinkRepository {

    override suspend fun searchNotes(userId: UserId, query: String): List<Note> {
        if (query.isBlank()) return emptyList()
        return noteDao.searchByTitle(userId.value, query)
            .map { it.toNote() }
    }

    override suspend fun searchTasks(query: String): List<Task> {
        if (query.isBlank()) return emptyList()
        return taskDao.searchByTitle(query)
            .map { it.toTask() }
    }

    override suspend fun getBacklinkNotes(noteId: String): List<Note> {
        return noteDao.getBacklinkNotes(noteId)
            .map { it.toNote() }
    }
}
