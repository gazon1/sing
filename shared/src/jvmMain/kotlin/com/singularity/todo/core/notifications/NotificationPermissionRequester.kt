package com.singularity.todo.core.notifications

import androidx.compose.runtime.Composable

/**
 * JVM implementation of [NotificationPermissionRequester].
 * JVM desktop apps do not have notification permission — always returns `true`.
 */
@Composable
actual fun rememberNotificationPermissionRequester(): NotificationPermissionRequester {
    val noOp = object : NotificationPermissionRequester {
        override val hasPermissions: Boolean get() = true
        override fun requestPermissions() = Unit
    }
    return noOp
}
