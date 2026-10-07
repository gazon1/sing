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
class AndroidNotifier(private val context: Context) : Notifier {

    /**
     * Whether this device will actually show a notification.
     *
     * ## Why this is measured rather than asserted
     *
     * It was `= true`, with a comment saying "the only question is the user's grant" —
     * and then it answered `true` without asking. On API 33+ the user can deny
     * `POST_NOTIFICATIONS`, and [post] returns immediately in that case. So the flag said
     * "supported" while the thing it promised was quietly not happening, and the caller
     * was right to believe it: [ReminderDelivery] checks this before posting and reports
     * `Posted`.
     *
     * The result was a reminder that fired, was logged as delivered, and appeared
     * nowhere — on every Android 13 device whose owner had said no. That is the exact
     * defect the capability flags were introduced to prevent, wearing the flag itself.
     *
     * ## Why it is a getter and not a `val`
     *
     * The grant can be revoked from system settings while the app runs, and Android can
     * be told to re-deliver the app after a permission change. A `val` captured at
     * construction would answer with the permission the app *started* with, which is
     * wrong in exactly the situation where the user is trying to fix it.
     *
     * Cost is one binder call, on a path that already builds a `Notification`.
     */
    override val isSupported: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    /**
     * Posts a notification with the given [tag], [title], and [body].
     *
     * Returns without posting when [isSupported] is false. A caller that checks the flag
     * first — which [ReminderDelivery] does — never reaches this; the guard is here so a
     * caller that does not check still cannot produce a notification the user will never
     * see while believing they were told they would.
     *
     * @param tag Unique notification tag (used as the second argument to [NotificationManagerCompat.notify]).
     *            For reminders this is `"reminder:${userId}:${reminderId}"`.
     * @param title Notification title.
     * @param body Notification body text.
     * @param viewId Optional SavedAgendaViewId deeplink target — carried as an extra in the
     *               launch PendingIntent and read by [MainActivity] to navigate to the correct view.
     */
    override fun post(tag: String, title: String, body: String, viewId: String?) {
        if (!isSupported) return
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
