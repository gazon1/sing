package com.singularity.todo.feature.calendar_sync.permission

import androidx.compose.runtime.Composable

/**
 * JVM stub for [CalendarPermissionRequester].
 *
 * Reports no permission and no support, rather than the `hasPermissions = true` this used
 * to return. That value existed only to let the settings screen render its controls, and
 * it shipped a desktop build of a dead control panel: every list was empty, and the enable
 * switch was a lie. A screen that needs a system calendar checks [isSupported] and shows
 * an "Android only" state instead.
 */
@Composable
actual fun rememberCalendarPermissionRequester(): CalendarPermissionRequester = object : CalendarPermissionRequester {
    override val hasPermissions: Boolean get() = false
    override val isSupported: Boolean get() = false
    override fun requestPermissions() {
        // No-op: there is nothing to request on a platform with no system calendar.
    }
}
