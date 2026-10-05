package com.singularity.todo.feature.calendar_sync.domain.port

import kotlinx.coroutines.flow.Flow

/**
 * The user's Google Calendar *choices*, as distinct from their Google *credentials*.
 *
 * ## Why this is not another method on [CalendarSyncRepository]
 *
 * [CalendarSyncRepository] describes the device-calendar projection: an enabled flag, a
 * target calendar id, a target app package, a status. None of those mean anything for
 * Google — there is no app package to pick, and the enabled flag governs a worker that
 * writes into a system provider. Folding Google's settings into it would mean a single
 * repository answering "is sync on?" for two features that answer it differently, and
 * every future reader would have to know which half they were looking at.
 *
 * ## What is *not* here
 *
 * Whether an account is connected. That is not a setting — it is whatever is in
 * [com.singularity.todo.feature.calendar_sync.auth.GoogleCredentialStore], and duplicating
 * it into DataStore would create a second source of truth that disagrees with the token
 * store the moment a grant expires. The settings screen reads the credential store for
 * "connected" and this repository for "which calendar".
 */
interface GoogleCalendarSettingsRepository {

    /**
     * The Google calendar the user chose to sync with, or null if none yet.
     *
     * The id is Google's opaque string, not a local row id.
     */
    fun observeSelectedCalendarId(): Flow<String?>

    /**
     * Records [calendarId] as the calendar to sync with, or clears the choice when null.
     *
     * Null is a first-class argument rather than an empty-string sentinel because the
     * repository's own reader returns `String?`, and a value that round-trips into a
     * non-null id is a bug waiting for the first disconnect.
     */
    suspend fun setSelectedCalendarId(calendarId: String?)

    /**
     * Whether events the app did not create should be pulled in as app tasks.
     *
     * Default true, and the default is the useful one: a user who connects Google is
     * usually there because something is already in it. Turning it off leaves the two-way
     * push untouched — the user's own tasks still reach Google — and stops only the import
     * of everything else.
     */
    fun observeImportForeignEvents(): Flow<Boolean>

    /** Sets [enabled] on the foreign-event import. */
    suspend fun setImportForeignEvents(enabled: Boolean)
}
