package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.feature.notes.Note

/**
 * Read-only state for the task logbook — a chronological feed of notes attached to a task.
 *
 * The logbook is updated reactively: any note attached to the task via [Note.taskId]
 * automatically appears in this feed without any manual refresh.
 */
sealed interface TaskLogbookState {
    /** No task is loaded yet, or the task has no notes. */
    data object Empty : TaskLogbookState

    /** Notes for the currently loaded task. Ordered by [Note.createdAt] descending. */
    data class Loaded(val notes: List<Note>) : TaskLogbookState

    /** Returns notes when [Loaded], or an empty list when [Empty]. */
    val allNotes: List<Note>
        get() = when (this) {
            is Empty -> emptyList()
            is Loaded -> notes
        }
}
