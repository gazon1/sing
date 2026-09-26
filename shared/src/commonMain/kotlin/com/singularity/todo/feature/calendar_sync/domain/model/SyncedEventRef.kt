package com.singularity.todo.feature.calendar_sync.domain.model

/**
 * Domain view of an already-synced task → system-calendar-event mapping.
 *
 * Pure read model used by [com.singularity.todo.feature.calendar_sync.domain.logic.SyncDiffMerge]
 * to decide Insert/Update/Delete operations. The Room entity backing this data lives in the
 * data layer ([com.singularity.todo.feature.calendar_sync.data.CalendarSyncTaskMapEntity]);
 * mapping happens at the repository/worker boundary.
 *
 * @param taskId Local task ID whose calendar event this reference describes.
 * @param calendarId System calendar the event currently lives in.
 * @param eventId System calendar event ID (absent from the device if the event was removed externally).
 * @param checksum Stable hash of the displayed fields at sync time — mismatch triggers an Update.
 */
data class SyncedEventRef(val taskId: String, val calendarId: String, val eventId: Long, val checksum: Int)
