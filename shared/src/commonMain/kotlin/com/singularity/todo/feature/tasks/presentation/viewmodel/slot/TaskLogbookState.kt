package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.timetracking.domain.TimeEntry

/**
 * A single entry in the task logbook — either a note or a time tracking row.
 */
sealed interface LogbookEntry {
    /** A note attached to the task. */
    data class NoteEntry(val note: Note) : LogbookEntry

    /** A time tracking entry for this task. */
    data class TimeEntryRow(val entry: TimeEntry) : LogbookEntry
}

/**
 * Read-only state for the task logbook — a chronological feed of notes and time entries
 * attached to a task.
 *
 * The logbook is updated reactively: any note or time entry attached to the task
 * automatically appears in this feed without any manual refresh.
 *
 * Entries are ordered by timestamp descending (newest first).
 */
sealed interface TaskLogbookState {
    /** No task is loaded yet, or the task has no logbook entries. */
    data object Empty : TaskLogbookState

    /**
     * Logbook entries for the currently loaded task.
     * Ordered by timestamp descending (newest first).
     */
    data class Loaded(val entries: List<LogbookEntry>) : TaskLogbookState

    /** Returns entries when [Loaded], or an empty list when [Empty]. */
    val allEntries: List<LogbookEntry>
        get() = when (this) {
            is Empty -> emptyList()
            is Loaded -> entries
        }
}
