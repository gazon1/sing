package com.singularity.todo.feature.calendar_sync.domain.model

import kotlin.jvm.JvmInline
import kotlin.time.Instant

/**
 * A Google Calendar event id.
 *
 * ## Why a value class and not [String]
 *
 * Google ids are opaque base64-ish strings roughly a kilobit long, and they are
 * interchangeable with *only* a Google event id. Passing them around as `String` let them be
 * swapped for a local task id or a calendar id by a compiler that had no opinion — which is
 * the mistake this type makes impossible. The device-calendar path uses `Long` ids; that is
 * a different type on purpose, and the two must not be unified.
 *
 * Persisted as the wrapped `String` in Room.
 */
@JvmInline
value class GoogleEventId(val value: String) {
    init {
        require(value.isNotBlank()) { "A Google event id cannot be blank" }
    }

    override fun toString(): String = value
}

/**
 * Google's own lifecycle for an event.
 *
 * ## Why cancellation is not deletion
 *
 * Google does not delete an event when it is removed from a calendar — it keeps the record
 * and sets [Cancelled]. That distinction is load-bearing: a cancelled event still arrives
 * in an incremental change listing with `showDeleted=true`, and a sync that treated it as
 * "gone forever" would drop the mapping on a transient cancellation and then re-create the
 * event on the next push. Cancelling our local mapping and leaving the task alone is the
 * correct response.
 */
enum class GoogleEventStatus {
    Confirmed,
    Tentative,
    Cancelled,
}

/**
 * A Google Calendar event as this feature understands it.
 *
 * Deliberately *not* a mirror of Google's wire format: the JSON DTO in the data layer maps
 * onto this, so a change to Google's payload shape stops here. Every field the app reasons
 * about is explicit and nullable, because a 3-way merge has to distinguish "absent" from
 * "present and empty".
 *
 * @param id Google's opaque event id.
 * @param calendarId Google's opaque calendar id.
 * @param taskId the task this event belongs to, or null when the app did not create it.
 *   Read from `extendedProperties.private`; a null here is what makes an event "foreign".
 * @param recurrenceRule Google's own RRULE string, kept **verbatim**. See below.
 * @param etag Google's revision tag, sent back as `If-Match` so a concurrent remote edit
 *   fails with 412 instead of being silently clobbered.
 * @param updatedAt Google's last-modified time, used by the caller to order a conflict
 *   before the merge is asked for one.
 */
data class GoogleEvent(
    val id: GoogleEventId,
    val calendarId: String,
    val title: String? = null,
    val description: String? = null,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    val allDay: Boolean? = null,
    val location: String? = null,
    /**
     * Google's RRULE string, stored and re-sent byte-for-byte.
     *
     * ## Why this is never parsed or rewritten
     *
     * The repository has no RFC 5545 implementation. `RecurrenceRuleMapper` parses the
     * app's *own* reminder vocabulary (`"WEEKLY:MON,WED,FRI"`), not the standard, and
     * `RruleGenerator` emits three of the eight standard fields — `INTERVAL`, `COUNT`,
     * `UNTIL`, `BYMONTH` and `EXDATE` are never produced, and `COUNT`/`UNTIL` are not in
     * the data model at all. Routing a real Google rule through either one would silently
     * rewrite a series the user depends on.
     *
     * So Google owns recurrence in this path: the string is compared for equality by the
     * merge and written back unchanged. Only app-originated new repeating tasks go through
     * the app's generator, and those remain limited to what it can express.
     */
    val recurrenceRule: String? = null,
    val status: GoogleEventStatus = GoogleEventStatus.Confirmed,
    val etag: String? = null,
    val updatedAt: Instant? = null,
    val taskId: String? = null,
) {
    /** True when this event is not cancelled, and so is still a live obligation. */
    val isLive: Boolean get() = status != GoogleEventStatus.Cancelled
}

/**
 * A calendar the account can write to, as offered to the user.
 */
data class GoogleCalendarSummary(
    val id: String,
    val summary: String,
    /** True when this is the account's primary calendar — offered as the default choice. */
    val isPrimary: Boolean = false,
    /** True when the account may create events here. */
    val canWrite: Boolean = true,
)

/**
 * One page of changes from an incremental listing.
 *
 * @param events the events in this page, in the order Google returned them.
 * @param nextPageToken more pages remain; [nextSyncToken] is only meaningful once this is
 *   null. A sync that persists a token from a partial walk corrupts its own cursor, because
 *   the token summarises a window it has not finished reading.
 * @param nextSyncToken the cursor to store **only** on the final page. Null while more
 *   pages remain.
 * @param requiresFullResync Google's `410 fullSyncRequired` was returned: the stored token
 *   is dead and a full listing must run. A normal outcome, not an error.
 */
data class ChangePage(
    val events: List<GoogleEvent>,
    val nextPageToken: String? = null,
    val nextSyncToken: String? = null,
    val requiresFullResync: Boolean = false,
) {
    val hasMorePages: Boolean get() = nextPageToken != null
}
