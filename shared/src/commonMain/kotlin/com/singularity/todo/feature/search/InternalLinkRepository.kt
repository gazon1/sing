package com.singularity.todo.feature.search

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.tasks.domain.model.Task

/**
 * Repository for internal link picker — searches notes and tasks by title
 * to enable Obsidian-style [[Note Title]] linking.
 */
interface InternalLinkRepository {
    suspend fun searchNotes(userId: UserId, query: String): List<Note>
    suspend fun searchTasks(query: String): List<Task>

    /** Returns notes that link TO the given noteId via note:// URL scheme. */
    suspend fun getBacklinkNotes(noteId: String): List<Note>
}
