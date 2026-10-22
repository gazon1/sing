package com.singularity.todo.feature.search.data

import com.singularity.todo.core.database.FtsColumn
import com.singularity.todo.core.database.FtsDao
import com.singularity.todo.core.database.FtsOffsets
import com.singularity.todo.core.database.NoteMatchRow
import com.singularity.todo.core.database.ProjectMatchRow
import com.singularity.todo.core.database.TagMatchRow
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.TaskMatchRow
import com.singularity.todo.core.database.toProject
import com.singularity.todo.core.database.toTask
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.core.search.IndexedRecord
import com.singularity.todo.core.search.SearchColumn
import com.singularity.todo.core.search.SearchTextIndex
import com.singularity.todo.core.search.TextMatchQuery
import com.singularity.todo.core.search.TextMatchRange
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.toNote
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.toTag
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.withExtras
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.zip
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
 *
 * ## The position of the match
 *
 * The rows come with the position of the match — FTS4's `offsets()`, read from
 * the index. Turning that position into a range in the characters of the text
 * happens here, because this is the place that holds the text of the column
 * the match is in: the title of a task, the description of a task, the title of
 * a note, the name of a project or of a tag.
 *
 * The conversion is bytes to characters, and it is the one step of this that a
 * title outside ASCII can get wrong.
 */
class SearchTextIndexImpl(
    private val ftsDao: FtsDao,
    private val taskDao: TaskDao,
    private val currentUser: ProfileAwareCurrentUser,
) : SearchTextIndex {

    override fun observeTasks(query: TextMatchQuery): Flow<List<IndexedRecord<Task>>> =
        currentUser.observeForCurrentUser { uid ->
            val matched: Flow<List<TaskMatchRow>> = ftsDao.watchTasks(uid.value, query)
            val tasks: Flow<List<Task>> = taskDao.withExtras(uid, matched.map { rows -> rows.map { it.task } })
            matched.zip(tasks) { rows, enriched ->
                val ranges = HashMap<String, List<TextMatchRange>>()
                rows.forEach { row -> ranges[row.task.id] = taskRanges(row) }
                enriched.map { task -> IndexedRecord(task, ranges[task.id.value] ?: emptyList()) }
            }
        }

    override fun observeNotes(query: TextMatchQuery): Flow<List<IndexedRecord<Note>>> =
        currentUser.observeForCurrentUser { uid ->
            ftsDao.watchNotes(uid.value, query).map { rows ->
                rows.map { row -> IndexedRecord(row.note.toNote(), noteRanges(row)) }
            }
        }

    override fun observeProjects(query: TextMatchQuery): Flow<List<IndexedRecord<Project>>> =
        currentUser.observeForCurrentUser { uid ->
            ftsDao.watchProjects(uid.value, query).map { rows ->
                rows.map { row -> IndexedRecord(row.project.toProject(), projectRanges(row)) }
            }
        }

    override fun observeTags(query: TextMatchQuery): Flow<List<IndexedRecord<Tag>>> =
        currentUser.observeForCurrentUser { uid ->
            ftsDao.watchTags(uid.value, query).map { rows ->
                rows.map { row -> IndexedRecord(row.tag.toTag(), tagRanges(row)) }
            }
        }

    override suspend fun recentTaskTitles(query: TextMatchQuery, limit: Int): List<Task> {
        val userId = currentUser.scopedUserId.value.value
        return ftsDao.searchTaskTitles(userId, query, limit).map { it.task.toTask() }
    }

    override suspend fun recentNoteTitles(query: TextMatchQuery, limit: Int): List<Note> {
        val userId = currentUser.scopedUserId.value.value
        return ftsDao.searchNoteTitles(userId, query, limit).map { it.note.toNote() }
    }

    /** Where the word is in a task: its title, or its description. */
    private fun taskRanges(row: TaskMatchRow): List<TextMatchRange> =
        FtsOffsets.ranges(
            row.offsets,
            listOf(
                FtsColumn(SearchColumn.TITLE, row.task.title),
                FtsColumn(SearchColumn.DESCRIPTION, row.task.description.orEmpty()),
            ),
        )

    /** Where the word is in a note: its title. */
    private fun noteRanges(row: NoteMatchRow): List<TextMatchRange> =
        FtsOffsets.ranges(row.offsets, listOf(FtsColumn(SearchColumn.TITLE, row.note.title)))

    /** Where the word is in a project: its name. */
    private fun projectRanges(row: ProjectMatchRow): List<TextMatchRange> =
        FtsOffsets.ranges(row.offsets, listOf(FtsColumn(SearchColumn.NAME, row.project.name)))

    /** Where the word is in a tag: its name. */
    private fun tagRanges(row: TagMatchRow): List<TextMatchRange> =
        FtsOffsets.ranges(row.offsets, listOf(FtsColumn(SearchColumn.NAME, row.tag.name)))
}
