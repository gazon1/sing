package com.singularity.todo.feature.calendar_sync.data

import com.singularity.todo.feature.calendar_sync.domain.port.CalendarProviderPort
import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncEvent

/**
 * JVM stub for [CalendarProviderPort].
 * Calendar sync is Android-only — this implementation does nothing and returns errors.
 */
class NoopCalendarProvider : CalendarProviderPort {

    override suspend fun getAvailableCalendars(): Result<Map<String, String>> =
        Result.failure(UnsupportedOperationException("Calendar sync is not available on this platform"))

    override suspend fun insertEvent(event: CalendarSyncEvent): Result<Long> =
        Result.failure(UnsupportedOperationException("Calendar sync is not available on this platform"))

    override suspend fun updateEvent(eventId: Long, event: CalendarSyncEvent): Result<Long> =
        Result.failure(UnsupportedOperationException("Calendar sync is not available on this platform"))

    override suspend fun deleteEvent(eventId: Long): Result<Unit> =
        Result.failure(UnsupportedOperationException("Calendar sync is not available on this platform"))

    override suspend fun queryEvents(
        calendarId: String?,
        fromMs: Long,
        toMs: Long,
    ): Result<Map<String, Long>> = Result.success(emptyMap())
}
