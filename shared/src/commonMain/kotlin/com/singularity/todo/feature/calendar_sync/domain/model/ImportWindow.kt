package com.singularity.todo.feature.calendar_sync.domain.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * How much of a Google calendar a full listing reads.
 *
 * ## Why a window at all
 *
 * A Google account holds years of history. A first sync with no lower bound would import all
 * of it: thousands of events, a long first run, and a list the user has to undo by hand.
 * So a full listing is bounded in both directions.
 *
 * ## Why the two ends differ
 *
 * **90 days forward** covers the period a calendar is actually for — what is coming up. The
 * far end is not arbitrary: a task with a due date beyond that horizon has nothing to sync to
 * yet, and will be picked up when it enters the window.
 *
 * **30 days back** is deliberately much smaller. Past events are context, not work: enough
 * to recognise "the dentist appointment you moved" or a series that started recently, and no
 * more. Importing a year of history buys nothing a user will act on.
 *
 * ## Why this is a value with both ends
 *
 * The upper bound is applied by the *listing* (`timeMax`), the lower by the listing too
 * (`timeMin`) and by the local cleanup that drops rows which have aged out. Keeping both on
 * one value means the two cannot drift apart — a window that reads three months forward and
 * then deletes at six months would silently import events it immediately discards.
 */
data class ImportWindow(val past: Duration = 30.days, val future: Duration = 90.days) {
    init {
        require(past >= Duration.ZERO) { "A window cannot reach further into the past than now" }
        require(future >= Duration.ZERO) { "A window cannot reach before now" }
    }

    /**
     * The instant to send as `timeMin` on a full listing.
     *
     * Null when the lower bound is "now", because Google rejects `timeMin` equal to the
     * current time as a range it treats as empty in some cases; sending nothing and letting
     * the upper bound do the work is the honest reading.
     */
    fun startInclusive(now: Instant): Instant = now - past

    /** The instant to send as `timeMax`, or null for an unbounded future. */
    fun endExclusive(now: Instant): Instant? =
        if (future == Duration.INFINITE) null else now + future

    /**
     * Whether an event starting at [startsAt] belongs in the window.
     *
     * Used by the local cleanup, so an event that ages out is deleted from the app rather
     * than lingering as an import the user never asked for. Both ends are inclusive of the
     * instant, so an event exactly on the boundary is kept rather than flickering.
     */
    fun contains(startsAt: Instant?, now: Instant): Boolean {
        if (startsAt == null) return false
        return startsAt >= startInclusive(now) &&
            (endExclusive(now)?.let { startsAt <= it } ?: true)
    }

    companion object {
        /**
         * The default: 30 days of past context, 90 days ahead.
         *
         * Aimed at "I need to see my near-term calendar and my recent past", not at
         * "archive my life into this app". A user who wants more raises it in settings, and
         * the next full listing picks it up.
         */
        val DEFAULT = ImportWindow()
    }
}
