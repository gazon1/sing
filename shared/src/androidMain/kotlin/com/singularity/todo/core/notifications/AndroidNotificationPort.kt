package com.singularity.todo.core.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Android implementation of [NotificationPort].
 *
 * Uses [AlarmManager.setExactAndAllowWhileIdle] for scheduling and
 * [NotificationManager] for posting. A [BroadcastReceiver] (registered in
 * AndroidManifest) receives the alarm broadcast and fires the notification.
 *
 * The notification taps open the app via [PendingIntent.FLAG_UPDATE_CURRENT].
 */
class AndroidNotificationPort(private val context: Context) : NotificationPort {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    override val isAvailable: Boolean = true

    override suspend fun scheduleAt(
        key: String,
        title: String,
        body: String,
        fireAtEpochMs: Long,
        payload: String?
    ) {
        val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            action = ACTION_REMINDER
            putExtra(EXTRA_KEY, key)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BODY, body)
            putExtra(EXTRA_PAYLOAD, payload)
        }

        val pending = PendingIntent.getBroadcast(
            context,
            key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    fireAtEpochMs,
                    pending
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    fireAtEpochMs,
                    pending
                )
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                fireAtEpochMs,
                pending
            )
        }
    }

    override suspend fun cancel(key: String) {
        val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            action = ACTION_REMINDER
        }
        val pending = PendingIntent.getBroadcast(
            context,
            key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pending)
    }

    override suspend fun cancelAll() {
        notificationManager.cancelAll()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Task Reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Gentle reminders for tasks"
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "singularity_reminders"
        const val ACTION_REMINDER = "com.singularity.todo.ACTION_REMINDER"
        const val EXTRA_KEY = "reminder_key"
        const val EXTRA_TITLE = "reminder_title"
        const val EXTRA_BODY = "reminder_body"
        const val EXTRA_PAYLOAD = "reminder_payload"
    }
}

/**
 * [BroadcastReceiver] that receives alarm broadcasts and posts notifications.
 * Subclass must be registered in `AndroidManifest.xml`:
 *
 * ```xml
 * <receiver
 *     android:name=".core.notifications.ReminderBroadcastReceiver"
 *     android:exported="false" />
 * ```
 */
class ReminderBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidNotificationPort.ACTION_REMINDER) return

        val key = intent.getStringExtra(AndroidNotificationPort.EXTRA_KEY) ?: return
        val title = intent.getStringExtra(AndroidNotificationPort.EXTRA_TITLE) ?: "Reminder"
        val body = intent.getStringExtra(AndroidNotificationPort.EXTRA_BODY) ?: ""

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val pending = PendingIntent.getActivity(
            context,
            key.hashCode(),
            context.packageManager.getLaunchIntentForPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = android.app.Notification.Builder(context, AndroidNotificationPort.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        @Suppress("UnspecifiedFlag")
        notificationManager.notify(key.hashCode(), notification)
    }
}
