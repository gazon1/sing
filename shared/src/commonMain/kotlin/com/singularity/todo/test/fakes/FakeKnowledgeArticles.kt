package com.singularity.todo.test.fakes

import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.feature.notes.KnowledgeArticle
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteKind
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Knowledge-article read the fake notes repository delegates to.
 *
 * It lives beside the repository instead of inside it: the repository class is
 * already at the size detekt allows, and every new read would push it over.
 */
internal class FakeKnowledgeArticles(
    private val store: InMemoryStore<Note>,
    private val currentUser: ProfileAwareCurrentUser,
) {
    /** The articles attached directly to a project. */
    fun observeForProject(
        projectId: ProjectId,
        limit: Int,
    ): Flow<List<Note>> = observe(limit) { it.projectId == projectId }

    /** The articles attached directly to a task. */
    fun observeForTask(
        taskId: TaskId,
        limit: Int,
    ): Flow<List<Note>> = observe(limit) { it.taskId == taskId }

    /**
     * The articles whose owner [belongsTo] — plain, non-folder notes carrying the
     * `## Sources` section, classified before the page is cut.
     *
     * The same rule as the production read: a non-positive limit is rejected instead
     * of reading an unbounded page.
     */
    private fun observe(
        limit: Int,
        belongsTo: (Note) -> Boolean,
    ): Flow<List<Note>> {
        require(limit > 0) { "A bounded read needs a positive limit, got $limit" }
        return currentUser.observeForCurrentUser { uid ->
            store.state.map { list ->
                list.values
                    .filter { note ->
                        note.userId == uid && note.deletedAt == null && note.archivedAt == null &&
                            belongsTo(note) &&
                            note.kind == NoteKind.Plain &&
                            !note.isFolder &&
                            KnowledgeArticle.carriesSources(note)
                    }.sortedWith(noteOrder)
                    .take(limit)
            }
        }
    }
}

/**
 * The order the notes come back in — newest first, then by id.
 *
 * The id breaks the tie, so two calls cannot swap two notes that were written in
 * the same second. The same order the store's query uses.
 */
internal val noteOrder: Comparator<Note> =
    compareByDescending<Note> { it.createdAt }
        .thenBy { it.id.value }
