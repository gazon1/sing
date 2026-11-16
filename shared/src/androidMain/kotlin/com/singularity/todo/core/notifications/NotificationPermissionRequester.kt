package com.singularity.todo.core.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Android implementation of [NotificationPermissionRequester].
 *
 * On API 33+ (Android 13): checks and requests `POST_NOTIFICATIONS` runtime permission.
 * On older Android: checks the system master notification toggle via
 * [NotificationManager.areNotificationsEnabled].
 *
 * ## Why `ON_RESUME` is the refresh point
 *
 * The state is re-read on `ON_RESUME`: returning from system settings delivers the
 * event, and the runtime permission dialog is answered by [launcher] directly, so the
 * state follows both paths without polling or a second state holder. Why that is
 * sufficient — the measurement, its platform limits, and the two accepted residuals —
 * is recorded in `docs/decisions/2026-11-16-on-resume-is-enough-for-the-notification-permission.md`.
 */
@Composable
actual fun rememberNotificationPermissionRequester(): NotificationPermissionRequester {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermissions by remember(context) {
        mutableStateOf(readNotificationPermission(context))
    }

    val refreshPermission = {
        val current = readNotificationPermission(context)
        if (current != hasPermissions) {
            hasPermissions = current
        }
    }

    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            hasPermissions = granted
        }

    DisposableEffect(lifecycleOwner, context) {
        val observer = notificationPermissionResumeObserver(refreshPermission)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return object : NotificationPermissionRequester {
        override val hasPermissions: Boolean get() = hasPermissions

        override fun requestPermissions() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

internal fun notificationPermissionResumeObserver(onResume: () -> Unit): LifecycleEventObserver =
    LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
            onResume()
        }
    }

internal fun readNotificationPermission(context: Context): Boolean =
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        else -> {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.areNotificationsEnabled()
        }
    }
