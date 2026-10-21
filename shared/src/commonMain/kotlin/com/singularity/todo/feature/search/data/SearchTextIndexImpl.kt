package com.singularity.todo.feature.search.data

import com.singularity.todo.core.database.FtsDao
import com.singularity.todo.core.database.FtsMatchQuery
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.toProject
import com.singularity.todo.core.database.toTask
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.core.search.SearchTextIndex
import com.singularity.todo.core.search.TextMatchQuery
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.toNote
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.toTag
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.withExtras
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The full-text index, read through the one port.
 *
 * The index is a read model over four tables, and this is the one place that
 * reads it. It used to be four places — one method on each of the four
 * repositories — and each of them held the index's DAO next to its own, so
 * there were two doors to the same rows and four copies of the same join.
 *
 * The records it returns are the records the repositories return: a task
 * carries its tags and its dependencies, exactly as it does everywhere else.
 */
class SearchTextIndexImpl(
    private val ftsDao: FtsDao,
    private val taskDao: TaskDao,
    private val currentUser: ProfileAwareCurrentUser,
) : SearchTextIndex {

    override fun observeTasks(query: TextMatchQuery): Flow<List<Task>> =
        currentUser.observeForCurrentUser { uid ->
            taskDao.withExtras(uid, ftsDao.watchTasks(uid.value, query))
        }

    override fun observeNotes(query: TextMatchQuery): Flow<List<Note>> =
        currentUser.observeForCurrentUser { uid ->
            ftsDao.watchNotes(uid.value, query).map { rows -> rows.map { it.toNote() } }
        }

    override fun observeProjects(query: TextMatchQuery): Flow<List<com.singularity.todo.feature.projects.domain.model.Project>> =
        currentUser.observeForCurrentUser { uid ->
            ftsDao.watchProjects(uid.value, query).map { rows -> rows.map { it.toProject() } }
        }

    override fun observeTags(query: TextMatchQuery): Flow<List<Tag>> =
        currentUser.observeForCurrentUser { uid ->
            ftsDao.watchTags(uid.value, query).map { rows -> rows.map { it.toTag() } }
        }

    override suspend fun recentTaskTitles(query: TextMatchQuery, limit: Int): List<Task> {
        val match = FtsMatchQuery.expression(query) ?: return emptyList()
        val userId = currentUser.scopedUserId.value.value
        return ftsDao.searchTaskTitlesByMatch(userId, match, limit).map { it.toTask() }
    }

    override suspend fun recentNoteTitles(query: TextMatchQuery, limit: Int): List<Note> {
        val match = FtsMatchQuery.expression(query) ?: return emptyList()
        val userId = currentUser.scopedUserId.value.value
        return ftsDao.searchNoteTitlesByMatch(userId, match, limit).map { it.toNote() }
    }
}
