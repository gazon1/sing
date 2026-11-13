package com.singularity.todo.core.notifications

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Opens the app's page in the system notification settings.
 *
 * `ACTION_APP_NOTIFICATION_SETTINGS` is the screen that carries the app's own
 * notification toggle. The application-details screen is the fallback: some OEM
 * builds do not resolve the notification action, and the details screen carries the
 * same switch one level deeper.
 */
class AndroidNotificationSettingsPort(private val context: Context) : NotificationSettingsPort {
    override val isSupported: Boolean = true

    override fun open() {
        val notificationSettings =
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(notificationSettings) }.isSuccess
        if (opened) return
        val appDetails =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(appDetails) }
    }
}
