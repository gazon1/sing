package com.singularity.todo.feature.calendarSync.domain.port

import com.singularity.todo.feature.calendarSync.domain.model.CalendarAppInfo

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
     * Whether this port can do its job on this platform.
     *
     * False means every call below is answered with "not available here", and the
     * screen has to say so instead of offering a control that does nothing. The
     * same reason [com.singularity.todo.feature.pomodoro.PomodoroTaskListProvider.isSupported]
     * exists: an empty list is an artifact of the platform, not a fact about the
     * user's calendar.
     */
    val isSupported: Boolean get() = true

    /**
     * Returns all installed apps that can handle calendar event intents.
     * The list is sorted by display name.
     */
    suspend fun listInstalled(): List<CalendarAppInfo>
}
