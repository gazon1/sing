package com.singularity.todo.feature.calendar_sync.permission

import androidx.compose.runtime.Composable

/**
 * Result of [rememberCalendarPermissionRequester]. Provides the current permission state
 * and a lambda to trigger the system permission dialog.
 *
 * On Android: backed by `ActivityResultContracts.RequestMultiplePermissions`.
 * On JVM: there is no system calendar to hold permissions for, so [hasPermissions] is
 * `false` and [isSupported] is `false` — see that property for why it is not `true`.
 */
@Composable
expect fun rememberCalendarPermissionRequester(): CalendarPermissionRequester

/**
 * Permission state holder returned by [rememberCalendarPermissionRequester].
 * Call [requestPermissions] to launch the system permission dialog.
 */
interface CalendarPermissionRequester {
    val hasPermissions: Boolean

    /**
     * Whether this platform can sync to a system calendar at all.
     *
     * ## Why the JVM stub must not report `hasPermissions = true`
     *
     * It used to, so that the settings screen's `if (!hasPermissions)` gate let the
     * controls render. That made desktop show a fully interactive panel over
     * `NoopCalendarProvider` / `NoopCalendarSyncRepository`: the calendar list rendered the
     * swallowed `UnsupportedOperationException` as "No calendars available", and the enable
     * switch appeared to work purely because the ViewModel optimistically updated state the
     * no-op repository had never accepted. A control that lies about its own effect is
     * worse than one that says it is unavailable, and the hard-coded `true` was also unsafe
     * for any future caller: it asserted a permission the app does not hold.
     *
     * So capability is now stated explicitly, and a screen that needs a system calendar
     * checks this first and says so.
     */
    val isSupported: Boolean

    fun requestPermissions()
}
