package com.singularity.todo.feature.timetracking.domain.model

import com.singularity.todo.feature.timetracking.domain.TimeEntry

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
    data class Running(val entry: TimeEntry, val elapsedMs: Long) : TaskTimeSlotState

    /**
     * Task has time entries but no active session.
     * @param entries All time entries for this task (newest first).
     * @param totalWorkMs Total work milliseconds across all completed entries.
     */
    data class Loaded(val entries: List<TimeEntry>, val totalWorkMs: Long) : TaskTimeSlotState

    /**
     * A write the user asked for did not happen, and [message] says why.
     *
     * Added because the three writes in [TaskTimeSlot] discarded their failures
     * with an empty `onFailure { }`, on the reasoning that "the UI updates from
     * the flow". That reasoning is wrong whenever the *write* fails, because
     * then there is nothing for the flow to update from: the chip stays on
     * Start, nothing logs, nothing is emitted, and the user's click looks
     * identical to a control that was never wired. Measured on
     * `TaskDetailCoordinatorGraphTest`'s sibling — `TaskTimeTrackingScenarioTest`
     * failed with a `ComposeTimeoutException` on *"no node with
     * `time_tracking_start` remains"*, a timeout that reads as a UI problem and
     * is actually a swallowed data failure, on the anonymous desktop harness
     * session where `startEntry` cannot succeed.
     *
     * A separate state rather than a nullable field on [Loaded], because the
     * whole point is that the failure is a different thing from "no data yet",
     * and a renderer that cannot tell them will draw them the same.
     */
    data class Error(val message: String) : TaskTimeSlotState
}
