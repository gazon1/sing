package com.singularity.todo.feature.calendar_sync.permission

import androidx.compose.runtime.Composable

/**
 * Result of [rememberCalendarPermissionRequester]. Provides the current permission state
 * and a lambda to trigger the system permission dialog.
 *
 * On Android: backed by `ActivityResultContracts.RequestMultiplePermissions`.
 * On JVM: always returns `hasPermissions = true`.
 */
@Composable
expect fun rememberCalendarPermissionRequester(): CalendarPermissionRequester

/**
 * Permission state holder returned by [rememberCalendarPermissionRequester].
 * Call [requestPermissions] to launch the system permission dialog.
 */
interface CalendarPermissionRequester {
    val hasPermissions: Boolean
    fun requestPermissions()
}
