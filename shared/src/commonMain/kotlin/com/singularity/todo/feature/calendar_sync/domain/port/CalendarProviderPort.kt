package com.singularity.todo.feature.calendar_sync.domain.port

import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncEvent

/**
 * Platform port for the system calendar (Android CalendarProvider).
 *
 * commonMain holds the interface; androidMain provides [AndroidCalendarProvider]
 * via ContentResolver + CalendarContract. jvmMain provides [NoopCalendarProvider].
 *
 * Operations are suspend functions that call the platform CalendarProvider.
 * All return Result<T> so the caller (typically [CalendarSyncWorker]) can
 * handle permission errors, calendar-not-found, etc.
 */
interface CalendarProviderPort {

    /**
     * Queries the available calendars visible to this app.
     * Returns a map of calendar ID (String) → display name.
     */
    suspend fun getAvailableCalendars(): Result<Map<String, String>>

    /**
     * Inserts [event] into the system calendar.
     * Returns the new system-calendar event ID (Long).
     */
    suspend fun insertEvent(event: CalendarSyncEvent): Result<Long>

    /**
     * Updates the existing system-calendar event [eventId] to match [event].
     * Returns the updated event ID (same as input).
     */
    suspend fun updateEvent(eventId: Long, event: CalendarSyncEvent): Result<Long>

    /**
     * Deletes the system-calendar event with ID [eventId].
     * Returns Unit on success.
     */
    suspend fun deleteEvent(eventId: Long): Result<Unit>

    /**
     * Queries the system calendar for events in the given time range.
     * Used for building the initial existing-map before diff.
     *
     * @param calendarId Filter to a specific calendar, or null for all calendars.
     * @param fromMs    Start of range (inclusive), UTC millis.
     * @param toMs      End of range (exclusive), UTC millis.
     * @return Map of taskId.value → system event ID.
     */
    suspend fun queryEvents(
        calendarId: String?,
        fromMs: Long,
        toMs: Long,
    ): Result<Map<String, Long>>
}
