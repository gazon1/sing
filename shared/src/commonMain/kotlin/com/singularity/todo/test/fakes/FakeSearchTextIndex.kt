package com.singularity.todo.test.fakes

import com.singularity.todo.core.search.IndexedRecord
import com.singularity.todo.core.search.SearchTextIndex
import com.singularity.todo.core.search.TextMatchQuery
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tasks.domain.model.Task
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.collections.immutable.persistentListOf

/**
 * The in-memory stand-in for the full-text index.
 *
 * The fake database has no index, so the fake index answers a text query by
 * reading the records and matching their text: a term matches a record when
 * one of the record's words starts with the term, and the terms are combined
 * the way the query says — every term, or any of them.
 *
 * This is the same rule the production index implements
 * ([com.singularity.todo.core.search.TextMatch]), so a test that passes here
 * is a test about the behaviour and not about the fake.
 *
 * ## The one thing the fake does not do
 *
 * The production index says *where* it found the word — the position of the
 * match, read from the index itself. The fake has no index and no tokenizer to
 * read the position from, so it answers with no ranges at all.
 *
 * That is not a gap in the fake to be papered over with a second
 * implementation of "where the word is": the position is the index's answer,
 * and a rule in Kotlin that guessed it would be a second answer to a question
 * only the index can answer. The position is asserted where the index is —
 * against a real SQLite, in `FtsOffsetsTest`.
 *
 * The *matching* is the same as the production one, diacritics included: the
 * fake answers with [com.singularity.todo.core.search.TextMatch], which folds
 * the diacritics the way the index folds them. A test that asserts the
 * behaviour of the diacritics belongs to the corpus (`FtsMatcherAgreementTest`),
 * which runs against a real SQLite. See
 * `docs/decisions/2026-10-23-the-rule-folds-the-diacritics-the-way-the-index-does.md`.
 *
 * The fake reads the repositories rather than a store of its own, so the
 * records it answers with are the records the repositories hold — a test that
 * puts a task into the fake repository and asks the index for it gets that
 * task, and not a different one.
 */
class FakeSearchTextIndex(
    private val taskRepo: FakeTaskRepository,
    private val noteRepo: FakeNotesRepository,
    private val projectRepo: FakeProjectsRepository,
    private val tagRepo: FakeTagsRepository,
) : SearchTextIndex {

    override fun observeTasks(query: TextMatchQuery): Flow<List<IndexedRecord<Task>>> =
        taskRepo.observeAll().map { tasks ->
            tasks.filter { task -> matchesTextMatch(query, task.title, task.description) }
                .map { task -> IndexedRecord(task, persistentListOf()) }
        }

    override fun observeNotes(query: TextMatchQuery): Flow<List<IndexedRecord<Note>>> =
        noteRepo.observeAll().map { notes ->
            notes.filter { note -> matchesTextMatch(query, note.title) }
                .map { note -> IndexedRecord(note, persistentListOf()) }
        }

    override fun observeProjects(query: TextMatchQuery): Flow<List<IndexedRecord<Project>>> =
        projectRepo.observeAll().map { projects ->
            projects.filter { project -> matchesTextMatch(query, project.name) }
                .map { project -> IndexedRecord(project, persistentListOf()) }
        }

    override fun observeTags(query: TextMatchQuery): Flow<List<IndexedRecord<Tag>>> =
        tagRepo.observeAll().map { tags ->
            tags.filter { tag -> matchesTextMatch(query, tag.name) }
                .map { tag -> IndexedRecord(tag, persistentListOf()) }
        }

    override suspend fun recentTaskTitles(query: TextMatchQuery, limit: Int): List<Task> =
        observeTasks(query).first().map { it.record }.take(limit)

    override suspend fun recentNoteTitles(query: TextMatchQuery, limit: Int): List<Note> =
        observeNotes(query).first().map { it.record }.take(limit)
}
