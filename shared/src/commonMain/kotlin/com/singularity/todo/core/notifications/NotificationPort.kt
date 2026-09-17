package com.singularity.todo.core.notifications

/**
 * Port for scheduling and cancelling system notifications.
 *
 * JVM/Linux: shells out to `notify-send` (immediate) and `at` (scheduled).
 * Android: uses `NotificationManager` + `AlarmManager`.
 */
interface NotificationPort {

    /** True when the notification backend is available on this platform. */
    val isAvailable: Boolean

    /**
     * Schedules a notification to fire at [fireAtEpochMs].
     * [payload] is passed back verbatim when the notification is tapped —
     * use it to carry the task/reminder ID.
     * [viewId] is an optional SavedAgendaViewId used by the Android implementation
     * to carry a deeplink target through the notification tap.
     */
    suspend fun scheduleAt(key: String, title: String, body: String, fireAtEpochMs: Long, payload: String? = null, viewId: String? = null)

    /** Cancels a scheduled notification by its [key]. No-op if already gone. */
    suspend fun cancel(key: String)

    /** Cancels all scheduled notifications. */
    suspend fun cancelAll()
}
