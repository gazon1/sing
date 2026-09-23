package com.singularity.todo.feature.calendar_sync.domain.model

/**
 * The result of comparing an existing system-calendar event with the current
 * desired state for a task. Determines what CalendarProvider operation to run.
 */
sealed interface SyncPlan {

    /** No action needed — existing event matches desired state. */
    data object NoOp : SyncPlan

    /** Insert [event] as a new system-calendar event. */
    data class Insert(val event: CalendarSyncEvent) : SyncPlan

    /**
     * Update the existing system-calendar event ([eventId]) to match [event].
     * [eventId] comes from the previously-inserted event.
     */
    data class Update(val eventId: Long, val event: CalendarSyncEvent) : SyncPlan

    /** Delete the system-calendar event with ID [eventId]. */
    data class Delete(val eventId: Long) : SyncPlan
}
