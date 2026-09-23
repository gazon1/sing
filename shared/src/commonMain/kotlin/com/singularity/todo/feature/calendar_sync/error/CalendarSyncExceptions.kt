package com.singularity.todo.feature.calendar_sync.error

/**
 * Sealed hierarchy of calendar-sync errors.
 *
 * Caught by [CalendarSyncWorker] and translated into [CalendarSyncStatus.Failed]
 * with a [FailureType] for tailored UI messaging.
 *
 * Inspired by Tasks.org layered `SyncException` hierarchy.
 */
sealed class CalendarSyncException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** READ_CALENDAR / WRITE_CALENDAR permission was revoked or never granted. */
    class PermissionRevokedException(
        message: String = "Calendar permission revoked",
    ) : CalendarSyncException(message)

    /** The target calendar was deleted from the system or is no longer accessible. */
    class CalendarNotFoundException(
        val calendarId: String,
    ) : CalendarSyncException("Calendar $calendarId not found")

    /** The selected calendar app package is not installed (user uninstalled it). */
    class CalendarAppMissingException(
        val packageName: String,
    ) : CalendarSyncException("Calendar app $packageName is not installed")

    /** Underlying [android.content.ContentResolver] threw a non-fatal exception. */
    class TransientSyncException(
        message: String,
        cause: Throwable? = null,
    ) : CalendarSyncException(message, cause)

    /** Network or I/O error talking to a remote calendar server. */
    class NetworkSyncException(
        message: String = "Network error during calendar sync",
        cause: Throwable? = null,
    ) : CalendarSyncException(message, cause)
}
