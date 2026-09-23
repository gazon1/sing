package com.singularity.todo.feature.calendar_sync.data

/**
 * Represents a calendar app that can receive event intents.
 *
 * @param packageName Unique package name (e.g. "com.google.android.calendar").
 * @param displayName Human-readable app name (e.g. "Google Calendar").
 */
data class CalendarAppInfo(
    val packageName: String,
    val displayName: String,
)
