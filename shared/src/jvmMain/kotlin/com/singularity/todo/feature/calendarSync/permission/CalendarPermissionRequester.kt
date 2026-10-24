package com.singularity.todo.feature.calendarSync.permission

import androidx.compose.runtime.Composable

/**
 * JVM stub for [CalendarPermissionRequester].
 *
 * Reports no permission, rather than the `hasPermissions = true` this used to return.
 * That value existed only to let the settings screen render its controls, and it
 * shipped a desktop build of a dead control panel: every list was empty, and the enable
 * switch was a lie.
 *
 * The stub used to carry an `isSupported` flag as well. The panel's gate is the
 * conjunction of the calendar seams' own flags, and the requester's answer was never
 * part of it — so the flag outlived its reader and is gone.
 */
@Composable
actual fun rememberCalendarPermissionRequester(): CalendarPermissionRequester = object : CalendarPermissionRequester {
    override val hasPermissions: Boolean get() = false
    override fun requestPermissions() {
        // No-op: there is nothing to request on a platform with no system calendar.
    }
}
