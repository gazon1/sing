package com.singularity.todo.feature.calendar_sync.error

import android.database.sqlite.SQLiteException
import java.io.IOException

/**
 * Android implementation of [translateExceptions].
 *
 * Catches the exception types that [android.content.ContentResolver] throws and
 * re-throws typed [CalendarSyncException] subclasses.
 */
actual inline fun <T> translateExceptions(block: () -> T): T {
    return try {
        block()
    } catch (e: SecurityException) {
        throw CalendarSyncException.PermissionRevokedException(
            e.message ?: "Calendar permission revoked",
        ).initCause(e)
    } catch (e: IllegalArgumentException) {
        // ContentResolver throws IllegalArgumentException with "Unknown URI" or
        // "No authority" when the target app can't handle CalendarContract.Events.CONTENT_URI
        val calendarEx = CalendarSyncException.CalendarAppMissingException("unknown")
        throw calendarEx.initCause(e)
    } catch (e: IllegalStateException) {
        // Cursor closed, ContentProviderClient disconnected, etc.
        throw CalendarSyncException.TransientSyncException(
            e.message ?: "Illegal state during sync",
            e,
        )
    } catch (e: SQLiteException) {
        throw CalendarSyncException.TransientSyncException(
            "Local database error during calendar sync",
            e,
        )
    } catch (e: IOException) {
        throw CalendarSyncException.NetworkSyncException(
            e.message ?: "Network error during calendar sync",
            e,
        )
    }
}
