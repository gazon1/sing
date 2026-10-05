package com.singularity.todo.feature.calendar_sync.domain.port

import com.singularity.todo.feature.calendar_sync.domain.model.ChangePage
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleCalendarSummary
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEvent
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventId

/**
 * Google Calendar, as this feature needs to talk to it.
 *
 * ## Why this is not an extension of [CalendarProviderPort]
 *
 * The device-calendar port assumes integer ids, whole-event replacement, no revision, no
 * cancellation, and a read model keyed by task. Google is a *peer* rather than a projection
 * of our tasks: ids are opaque strings, updates are partial, an `etag` gives real optimistic
 * concurrency, an event is cancelled rather than deleted, and change streams are per-calendar
 * cursors. Bending one port to cover both would give the device path parameters it cannot
 * honour. Two honest ports cost one file and keep both call sites readable.
 *
 * ## What the caller must guarantee
 *
 * Every call here is scoped to a calendar the user chose. An implementation must not write
 * to any other calendar, and must not invent one when the caller did not name it.
 */
interface CalendarEventSource {

    /**
     * Calendars this account can write to, for the picker.
     *
     * Never returns calendars the account cannot write to: offering one produces a
     * permission error at the first write, which reads to the user as a broken app.
     */
    suspend fun listCalendars(): List<GoogleCalendarSummary>

    /**
     * One page of changes for [calendarId], continuing from [syncToken].
     *
     * A null [syncToken] asks for a full listing. The returned page carries
     * [ChangePage.nextSyncToken] **only on the last page** — persisting a token from a
     * partial walk records a cursor summarising a window that has not been read, and the
     * changes in the unread pages are then skipped forever.
     *
     * [ChangePage.requiresFullResync] is Google's `410 fullSyncRequired`: a normal outcome
     * meaning the stored token is dead, not a transport failure.
     */
    suspend fun fetchChanges(
        calendarId: String,
        syncToken: String?,
    ): ChangePage

    /**
     * Creates [event] in [calendarId] and returns the stored form — the id and the etag the
     * next write must present.
     *
     * The returned value is authoritative, not [event]: Google may normalise a title or a
     * time zone, and writing back what we *asked* for instead of what Google *stored* would
     * make every later merge see a phantom difference.
     */
    suspend fun insert(calendarId: String, event: GoogleEvent): GoogleEvent

    /**
     * Applies a partial update to [eventId], guarded by [etag].
     *
     * Partial rather than whole-event on purpose: a whole-event write would clobber a field
     * the app has no reason to own, and Google charges by the fields you send.
     *
     * A stale [etag] fails rather than overwriting — see
     * [com.singularity.todo.feature.calendar_sync.error.CalendarSyncException.TransientSyncException].
     * The caller re-reads and re-plans, once.
     */
    suspend fun patch(calendarId: String, eventId: GoogleEventId, etag: String?, event: GoogleEvent): GoogleEvent

    /** Reads one event, for the re-plan after a conflicting write. */
    suspend fun get(calendarId: String, eventId: GoogleEventId): GoogleEvent

    /**
     * Cancels [eventId].
     *
     * Google does not delete an event when it leaves a calendar — it keeps the record with
     * `status: cancelled`. That is what a user who removed an event means, and it is what
     * still arrives in an incremental listing, so cancelling rather than deleting is what
     * keeps a later pull from re-creating the event.
     */
    suspend fun cancel(calendarId: String, eventId: GoogleEventId, etag: String?)
}
