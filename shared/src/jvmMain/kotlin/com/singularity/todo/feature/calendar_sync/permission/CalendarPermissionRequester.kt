package com.singularity.todo.feature.calendar_sync.permission

import androidx.compose.runtime.Composable

/**
 * JVM stub for [CalendarPermissionRequester].
 * Calendar sync is Android-only — JVM always reports permissions as granted.
 */
@Composable
actual fun rememberCalendarPermissionRequester(): CalendarPermissionRequester = object : CalendarPermissionRequester {
    override val hasPermissions: Boolean get() = true
    override fun requestPermissions() {
        // No-op on JVM
    }
}
