package com.singularity.todo.feature.calendar_sync.domain.repository

import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncStatus
import kotlinx.coroutines.flow.Flow

/**
 * Repository for calendar-sync state: enabled flag, selected calendar ID,
 * last sync timestamp, and current status.
 *
 * The actual task→system-event mapping is stored locally via
 * [CalendarSyncTaskMapDao] (Room), not in this repository.
 */
interface CalendarSyncRepository {

    /**
     * Whether calendar sync is enabled.
     * When false, the sync worker does nothing.
     */
    fun observeEnabled(): Flow<Boolean>

    /** Sets the enabled flag. */
    suspend fun setEnabled(enabled: Boolean)

    /**
     * The Android calendar ID to sync into (e.g. "primary" or numeric string).
     * Must be set before sync can run.
     */
    fun observeTargetCalendarId(): Flow<String?>

    /** Sets the target calendar ID. */
    suspend fun setTargetCalendarId(calendarId: String)

    /**
     * The UTC epoch millis of the last successful sync.
     * Null if never synced.
     */
    fun observeLastSyncedAt(): Flow<Long?>

    /** Records a successful sync timestamp. */
    suspend fun setLastSyncedAt(ts: Long)

    /** Current [CalendarSyncStatus]. */
    fun observeStatus(): Flow<CalendarSyncStatus>

    /** Updates the current status. */
    suspend fun setStatus(status: CalendarSyncStatus)
}
