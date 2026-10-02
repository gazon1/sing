package com.singularity.todo.feature.timetracking.domain.model

import com.singularity.todo.feature.timetracking.TimeEntry

/**
 * UI state for the task time-tracking slot.
 */
sealed interface TaskTimeSlotState {
    /**
     * No task is loaded yet.
     */
    data object Loading : TaskTimeSlotState

    /**
     * Task is loaded but no time tracking data available.
     */
    data object Idle : TaskTimeSlotState

    /**
     * Active time tracking session.
     * @param entry The running time entry.
     * @param elapsedMs milliseconds elapsed since [entry][TimeEntry.startedAt], updated by the UI.
     */
    data class Running(
        val entry: TimeEntry,
        val elapsedMs: Long,
    ) : TaskTimeSlotState

    /**
     * Task has time entries but no active session.
     * @param entries All time entries for this task (newest first).
     * @param totalWorkMs Total work milliseconds across all completed entries.
     */
    data class Loaded(
        val entries: List<TimeEntry>,
        val totalWorkMs: Long,
    ) : TaskTimeSlotState
}
