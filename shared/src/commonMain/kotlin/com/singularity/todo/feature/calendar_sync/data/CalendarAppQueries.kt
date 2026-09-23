package com.singularity.todo.feature.calendar_sync.data

/**
 * Port for enumerating installed calendar apps on the current platform.
 *
 * Used by the calendar app picker UI to let the user choose which
 * calendar app (Google Calendar, Samsung Calendar, etc.) to sync to.
 *
 * Android: queries `PackageManager` for apps that handle `ACTION_INSERT` on `CalendarContract.Events`.
 * JVM: returns an empty list (calendar sync is Android-only).
 */
interface CalendarAppQueries {

    /**
     * Returns all installed apps that can handle calendar event intents.
     * The list is sorted by display name.
     */
    suspend fun listInstalled(): List<CalendarAppInfo>
}
