package com.singularity.todo.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Android implementation of [NotificationPort].
 *
 * Note: The [scheduleAt] and [cancel] methods are stubs — reminder scheduling
 * is now handled by [AlarmManagerReminderScheduler][com.singularity.todo.feature.reminders.AlarmManagerReminderScheduler]
 * which posts notifications via [AndroidNotifier][AndroidNotifier].
 * This class remains to satisfy the [NotificationPort] interface.
 *
 * Actual notification posting (used by [AlarmReceiver][com.singularity.todo.feature.alarms.AlarmReceiver])
 * is done via [AndroidNotifier].
 */
class AndroidNotificationPort(private val context: Context) : NotificationPort {

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    override val isAvailable: Boolean = true

    override suspend fun scheduleAt(key: String, title: String, body: String, fireAtEpochMs: Long, payload: String?, viewId: String?) {
        // Stub: reminder scheduling moved to AlarmManagerReminderScheduler + AlarmReceiver.
        // No-op here.
    }

    override suspend fun cancel(key: String) {
        // Stub: reminder cancellation moved to AlarmManagerReminderScheduler.
        // No-op here.
    }

    override suspend fun cancelAll() {
        notificationManager.cancelAll()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Task Reminders",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Gentle reminders for tasks"
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "singularity_reminders"
    }
}
