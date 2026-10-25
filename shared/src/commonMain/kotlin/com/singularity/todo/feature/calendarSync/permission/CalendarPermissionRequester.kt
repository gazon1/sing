package com.singularity.todo.feature.calendarSync.permission

import androidx.compose.runtime.Composable

/**
 * Result of [rememberCalendarPermissionRequester]. Provides the current permission state
 * and a lambda to trigger the system permission dialog.
 *
 * On Android: backed by `ActivityResultContracts.RequestMultiplePermissions`.
 * On JVM: there is no system calendar to hold permissions for, so [hasPermissions] is
 * `false` and [requestPermissions] does nothing — see [hasPermissions] for why it is
 * not `true`.
 */
@Composable
expect fun rememberCalendarPermissionRequester(): CalendarPermissionRequester

/**
 * Permission state holder returned by [rememberCalendarPermissionRequester].
 * Call [requestPermissions] to launch the system permission dialog.
 */
interface CalendarPermissionRequester {

    /**
     * Whether the calendar permission is held right now.
     *
     * ## Why the JVM stub must not report `true`
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
     * ## Why there is no `isSupported` here any more
     *
     * The port used to carry one, and the screen used to gate the whole panel on it.
     * The panel's gate is now the conjunction of the calendar seams' own flags — the
     * provider, the settings repository, the app queries and the scheduler — because
     * each of those answers for the job it actually does, and the permission requester
     * answers a different question: whether this device may *ask* for the permission.
     *
     * The two agreed on every platform the app ships on, and the flag outlived its
     * reader: a capability flag with no reader is a promise nobody keeps. So it is gone,
     * and the permission case is what [hasPermissions] and [requestPermissions] are for.
     */
    val hasPermissions: Boolean

    fun requestPermissions()
}
