package com.singularity.todo.core.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Thin Koin-injectable wrapper around [NotificationManagerCompat] for posting notifications.
 *
 * Handles channel creation, launch PendingIntent, and deeplink carry-over.
 * Scheduling is handled separately by [AlarmManagerReminderScheduler][com.singularity.todo.feature.reminders.AlarmManagerReminderScheduler].
 *
 * @param context Android [Context] (application or activity scoped).
 */
class AndroidNotifier(private val context: Context) {

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    /**
     * Posts a notification with the given [tag], [title], and [body].
     *
     * @param tag Unique notification tag (used as the second argument to [NotificationManagerCompat.notify]).
     *            For reminders this is `"reminder:${userId}:${reminderId}"`.
     * @param title Notification title.
     * @param body Notification body text.
     * @param viewId Optional SavedAgendaViewId deeplink target — carried as an extra in the
     *               launch PendingIntent and read by [MainActivity] to navigate to the correct view.
     */
    fun post(tag: String, title: String, body: String, viewId: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            if (viewId != null) {
                putExtra(EXTRA_DEEPLINK_VIEW_ID, viewId)
            }
        }

        val pending = PendingIntent.getActivity(
            context,
            tag.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        @Suppress("UnspecifiedFlag")
        notificationManager.notify(tag, tag.hashCode(), notification)
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
        const val EXTRA_DEEPLINK_VIEW_ID = "reminder_deeplink_view_id"
    }
}
