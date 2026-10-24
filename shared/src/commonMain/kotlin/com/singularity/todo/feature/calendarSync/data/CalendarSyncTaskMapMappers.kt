package com.singularity.todo.feature.calendarSync.data

import com.singularity.todo.feature.calendarSync.domain.model.SyncedEventRef

/**
 * Entity → domain mappers for the calendar_sync task-map table. Kept in the data layer
 * so the domain diff logic never depends on Room types.
 */
internal fun CalendarSyncTaskMapEntity.toSyncedEventRef(): SyncedEventRef = SyncedEventRef(
    taskId = taskId,
    calendarId = calendarId,
    eventId = eventId,
    checksum = checksum,
)
