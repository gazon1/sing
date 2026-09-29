package com.singularity.todo.core.notifications

import androidx.compose.runtime.Composable

/**
 * Result of [rememberNotificationPermissionRequester]. Provides the current permission state
 * and a lambda to trigger the system permission dialog.
 *
 * On Android: backed by `ActivityResultContracts.RequestPermission` on API 33+ (POST_NOTIFICATIONS).
 * On older Android: backed by `NotificationManager.areNotificationsEnabled()`.
 * On JVM: always returns `hasPermissions = true`.
 */
@Composable
expect fun rememberNotificationPermissionRequester(): NotificationPermissionRequester

/**
 * Permission state holder returned by [rememberNotificationPermissionRequester].
 * Call [requestPermissions] to launch the system permission dialog (Android 13+ only).
 */
interface NotificationPermissionRequester {
    /**
     * Whether the app currently has permission to post notifications.
     *
     * On Android 13+ this reflects the `POST_NOTIFICATIONS` runtime grant.
     * On older Android this reflects the system notification master toggle.
     * On JVM this is always `true`.
     */
    val hasPermissions: Boolean

    /**
     * Launches the system permission dialog for POST_NOTIFICATIONS.
     * No-op on platforms that do not require runtime permission (JVM, older Android).
     *
     * The result is reflected in [hasPermissions] on the next composition.
     */
    fun requestPermissions()
}
