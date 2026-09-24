package com.singularity.todo.feature.calendar_sync.domain.model

import com.singularity.todo.feature.calendar_sync.error.FailureType

/**
 * Current state of the calendar sync subsystem.
 *
 * Exposed via [CalendarSyncRepository.observeStatus].
 */
sealed interface CalendarSyncStatus {

    /** Sync is disabled — user has not granted permission or turned it off. */
    data object Disabled : CalendarSyncStatus

    /** Sync is idle. [lastSyncedAt] is the UTC epoch millis of the last successful sync. */
    data class Idle(val lastSyncedAt: Long?) : CalendarSyncStatus

    /** Sync is currently running. */
    data object Syncing : CalendarSyncStatus

    /**
     * Sync failed with [reason].
     * The [type] carries a [FailureType] for UI-tailored error messaging and recovery actions.
     * The system will retry with back-off; this persists until the next success.
     */
    data class Failed(val reason: String, val type: FailureType = FailureType.Unknown) : CalendarSyncStatus
}
