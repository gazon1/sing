package com.singularity.todo.core.notifications

import androidx.compose.runtime.Composable

/**
 * JVM implementation of [NotificationPermissionRequester].
 * JVM desktop apps do not have notification permission — always returns `true`.
 */
@Composable
actual fun rememberNotificationPermissionRequester(): NotificationPermissionRequester {
    return object : NotificationPermissionRequester {
        override val hasPermissions: Boolean get() = true
        override fun requestPermissions() {
            // No-op on JVM.
        }
    }
}
