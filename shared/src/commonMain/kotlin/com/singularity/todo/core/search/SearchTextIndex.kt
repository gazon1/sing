package com.singularity.todo.core.search

import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tasks.domain.model.Task
import kotlinx.coroutines.flow.Flow

/**
 * The full-text index, as the application reads it.
 *
 * ## Why this is one port and not a method on four repositories
 *
 * The index is a read model over four tables. Asking it for a word used to
 * mean four methods — `observeByTextMatch` on the task, note, project and tag
 * repositories — and four implementations, each of which held its own DAO
 * *and* the index's DAO: two doors to the same rows, and the same join, the
 * same tokenizer and the same `docid` rule written four times.
 *
 * One port says one thing in one place. The implementations that were four
 * copies of the same lookup are one, and the repositories are left with the
 * rows they own and nothing else.
 *
 * ## The records it returns
 *
 * The records are the same records the repositories return — a task carries
 * its tags and the tasks it depends on, exactly as it does everywhere else.
 * The index answers "which records carry this word"; it does not answer
 * "what is a task".
 *
 * ## The records come with the place the word was found
 *
 * Every record the index returns comes with the ranges of the match — where in
 * the record the word was found. The index is the only one that knows: it
 * holds the tokenizer and the column the token is in, and a search that shows
 * the word in the result has to be told where it is.
 *
 * The alternative — asking the rule in Kotlin where the word is — is a second
 * answer to a question the index has already answered. See [IndexedRecord].
 *
 * ## The user
 *
 * The index is scoped to the current user, like the repositories it replaces.
 * A search never crosses a profile, and the scoping is not something a caller
 * can forget to ask for.
 */
interface SearchTextIndex {

    /** The tasks whose title or description carry the word, and where they carry it. */
    fun observeTasks(query: TextMatchQuery): Flow<List<IndexedRecord<Task>>>

    /** The notes whose title carries the word, and where they carry it. */
    fun observeNotes(query: TextMatchQuery): Flow<List<IndexedRecord<Note>>>

    /** The projects whose name carries the word, and where they carry it. */
    fun observeProjects(query: TextMatchQuery): Flow<List<IndexedRecord<Project>>>

    /** The tags whose name carries the word, and where they carry it. */
    fun observeTags(query: TextMatchQuery): Flow<List<IndexedRecord<Tag>>>

    /**
     * The tasks whose title carries the word, most recently updated first.
     *
     * For offering a title to a link — a list of suggestions, not a list of
     * results, and a suggestion that is not the most recent is not a
     * suggestion.
     */
    suspend fun recentTaskTitles(query: TextMatchQuery, limit: Int): List<Task>

    /** The notes whose title carries the word, most recently updated first. See [recentTaskTitles]. */
    suspend fun recentNoteTitles(query: TextMatchQuery, limit: Int): List<Note>
}
