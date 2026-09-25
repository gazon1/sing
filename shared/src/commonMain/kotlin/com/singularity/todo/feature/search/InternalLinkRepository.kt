package com.singularity.todo.feature.search

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
    suspend fun searchNotes(query: String): List<Note>
    suspend fun searchTasks(query: String): List<Task>

    /** Returns notes that link TO the given noteId via note:// URL scheme, for the active user. */
    suspend fun getBacklinkNotes(noteId: String): List<Note>

    /** Returns tasks that link TO the given taskId via task:// URL scheme, for the active user. */
    suspend fun getBacklinkTasks(taskId: String): List<Task>

    /** Returns notes that link TO the given taskId via task:// URL scheme, for the active user. */
    suspend fun getNotesLinkingToTask(taskId: String): List<Note>
}
