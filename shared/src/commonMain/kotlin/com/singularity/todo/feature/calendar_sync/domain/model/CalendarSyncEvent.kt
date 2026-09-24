package com.singularity.todo.feature.calendar_sync.domain.model

import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * A task mapped to a system calendar event (Android CalendarProvider).
 *
 * This is the canonical representation used by [CalendarProviderPort] operations.
 * All timestamps are UTC millis since epoch.
 *
 * @param taskId        Source task ID (for deep-link construction).
 * @param calendarId    Target Android calendar ID (e.g. "primary" or numeric string).
 * @param eventId      Existing system-calendar event ID (null = not yet synced).
 * @param title        Event title.
 * @param description  Event description. Includes `singularity://task/{taskId.value}` deep-link.
 * @param startMs     Event start in UTC millis.
 * @param endMs       Event end in UTC millis (exclusive for all-day events).
 * @param allDay      True → all-day event (date-only, no time).
 * @param rrule       RRULE string for recurring events (null = not recurring).
 * @param color       24-bit color int (0xRRGGBB) or null to use calendar default.
 */
data class CalendarSyncEvent(
    val taskId: TaskId,
    val calendarId: String,
    val eventId: Long?,
    val title: String,
    val description: String,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val rrule: String?,
    val color: Long?,
    /**
     * Stable hash of the event fields used by [SyncDiffMerge] to detect unchanged events.
     * Computed by [com.singularity.todo.feature.calendar_sync.domain.logic.CalendarEventMapper]
     * when the event is first mapped.
     */
    val checksum: Int = 0,
) {
    companion object {
        const val DEEP_LINK_SCHEME = "singularity"
        const val DEEP_LINK_HOST = "task"

        fun deepLink(taskId: TaskId): String = "$DEEP_LINK_SCHEME://$DEEP_LINK_HOST/${taskId.value}"
    }
}
