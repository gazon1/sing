package com.singularity.todo.feature.search.data

import com.singularity.todo.core.database.NoteDao
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.toTask
import com.singularity.todo.core.search.SearchTextIndex
import com.singularity.todo.core.search.TextMatchQuery
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.toNote
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.search.domain.port.InternalLinkRepository
import com.singularity.todo.feature.tasks.domain.model.Task

class InternalLinkRepositoryImpl(
    private val noteDao: NoteDao,
    private val taskDao: TaskDao,
    private val textIndex: SearchTextIndex,
    private val currentUser: ProfileAwareCurrentUser,
) : InternalLinkRepository {

    override suspend fun searchNotes(query: String): List<Note> {
        if (query.isBlank()) return emptyList()
        return textIndex.recentNoteTitles(TextMatchQuery(listOf(query)), limit = SUGGESTION_LIMIT)
    }

    override suspend fun searchTasks(query: String): List<Task> {
        if (query.isBlank()) return emptyList()
        return textIndex.recentTaskTitles(TextMatchQuery(listOf(query)), limit = SUGGESTION_LIMIT)
    }

    override suspend fun getBacklinkNotes(noteId: String): List<Note> {
        val userId = currentUser.scopedUserId.value.value
        return noteDao.getBacklinkNotes(noteId, userId).map { it.toNote() }
    }

    override suspend fun getBacklinkTasks(taskId: String): List<Task> {
        val userId = currentUser.scopedUserId.value.value
        return taskDao.getBacklinkTasks(taskId, userId).map { it.toTask() }
    }

    override suspend fun getNotesLinkingToTask(taskId: String): List<Note> {
        val userId = currentUser.scopedUserId.value.value
        return noteDao.getNotesLinkingToTask(taskId, userId).map { it.toNote() }
    }

    private companion object {
        /** How many titles a suggestion list holds. */
        const val SUGGESTION_LIMIT = 20
    }
}
