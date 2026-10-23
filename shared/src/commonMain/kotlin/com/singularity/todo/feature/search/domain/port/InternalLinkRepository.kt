package com.singularity.todo.feature.search.domain.port

import com.singularity.todo.core.search.SearchMode
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.tasks.domain.model.Task

/**
 * Repository for internal link picker — searches notes and tasks by title
 * to enable Obsidian-style [[Note Title]] linking.
 *
 * All queries are scoped to the active user resolved from [com.singularity.todo.feature.profile.ProfileAwareCurrentUser].
 * Per ADR 2026-09-22-explicit-overload-removal, userId is no longer a method parameter.
 */
interface InternalLinkRepository {

    /**
     * The notes offered for a link.
     *
     * @param query the text to look for.
     * @param mode the mode the titles are matched in (F6, F8) — the surface
     *   states it, so that the suggestion list never answers from a rule the
     *   caller did not name.
     */
    suspend fun searchNotes(query: String, mode: SearchMode): List<Note>

    /** The tasks offered for a link. See [searchNotes]. */
    suspend fun searchTasks(query: String, mode: SearchMode): List<Task>

    /** Returns notes that link TO the given noteId via note:// URL scheme, for the active user. */
    suspend fun getBacklinkNotes(noteId: String): List<Note>

    /** Returns tasks that link TO the given taskId via task:// URL scheme, for the active user. */
    suspend fun getBacklinkTasks(taskId: String): List<Task>

    /** Returns notes that link TO the given taskId via task:// URL scheme, for the active user. */
    suspend fun getNotesLinkingToTask(taskId: String): List<Note>
}
