package com.singularity.todo.feature.calendar_sync.domain.logic

import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEvent
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventId
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * What the planner needs to know about one already-synced event.
 *
 * ## Why this exists rather than taking the Room row
 *
 * The planner is pure domain logic, and taking a `GoogleEventShadowEntity` would have made
 * `domain` import `data` — which `ArchitectureTest."feature domain does not import
 * presentation or data layers"` rejects, correctly. The dependency points the other way: the
 * entity knows about the table, and this type knows about sync. [sync.GoogleSyncEngine] maps
 * one to the other at the boundary.
 *
 * A domain read model rather than passing the base shadow alone, because the planner also
 * needs `etag`, `eventId` and `calendarId` — the three fields that make a patch *possible*,
 * and none of which the planner would be entitled to reach into the row for.
 */
data class EventShadowRef(
    val eventId: String,
    val calendarId: String,
    /** Null for an event we have not adopted. */
    val taskId: String?,
    /** Google's revision, replayed as `If-Match` so a concurrent edit cannot be clobbered. */
    val etag: String?,
    /** The ancestor: what both sides last agreed. */
    val base: EventShadow,
    /**
     * When the user cancelled this event in Google, or null while it is live.
     *
     * Carried so the planner can answer "does this task already have an event?" without
     * mistaking "cancelled" for "never had one".
     */
    val cancelledAt: Long? = null,
)

/**
 * One write the sync wants to make in Google.
 *
 * Split into two rather than modelled as a sealed "upsert" because the two differ in where
 * the id comes from: an [Insert] has to mint one, a [Patch] has to present an `etag` it
 * does not own. Collapsing them hides the only field that makes a patch safe.
 */
sealed interface GooglePushAction {

    /** A task that has no Google event yet. */
    data class Insert(val taskId: TaskId, val event: GoogleEvent) : GooglePushAction

    /** An existing event whose app-owned fields no longer match the shadow. */
    data class Patch(val taskId: TaskId, val event: GoogleEvent, val etag: String?) : GooglePushAction
}

/**
 * Works out which local tasks Google does not yet agree with.
 *
 * ## Why this exists as a pure planner
 *
 * The pull walk can only ever see events that are *already in Google*, so it is structurally
 * incapable of noticing two whole classes of divergence: a task with no event at all, and a
 * task whose fields moved while Google stayed still. Both need a walk over the local side,
 * which is what this is. Keeping it a pure function of `(desired, shadows)` is what makes
 * those two cases testable without a task table, an HTTP client, or a database.
 *
 * ## Why the payload is `desired` over the shadow and not `desired` alone
 *
 * A patch replaces the fields it sends, and Google's `patch` is not a full replace — so
 * anything *not* sent survives while anything sent is overwritten. The app has no model for
 * `location`, and it deliberately does not parse `recurrenceRule`; sending either from a
 * task would blank a real location on the user's calendar and flatten a series. So the
 * payload keeps those two from the shadow and takes only the app-owned fields from the
 * task. That asymmetry is the whole reason [desiredOverShadow] exists.
 *
 * ## No deletes
 *
 * A task that no longer belongs on the calendar is not removed by this planner. That is
 * deliberate and it is the asymmetry that keeps the feature safe: a pull walk that decides
 * "this event is gone" from an empty or truncated page would delete a user's real calendar
 * entries, and the cost of being wrong is not symmetric with the cost of being late.
 */
object GooglePushPlanner {

    /**
     * The fields the app owns and therefore writes. Everything else in an event is Google's.
     *
     * Written as a list so the "keep the rest" rule in [desiredOverShadow] has one place to
     * name; a new app-owned field has to be added here or it silently never syncs out.
     */
    private val APP_OWNED_FIELDS = listOf("title", "description", "startsAt", "endsAt", "allDay")

    /**
     * The writes [desired] implies, given the [shadows] already recorded.
     *
     * @param desired one [GoogleEvent] per task that should be on the calendar. Its `id` is
     *   ignored — nothing local knows a Google event id.
     * @param shadows every shadow for this profile, including ones whose event has since
     *   been cancelled; those still carry the id needed to patch.
     */
    fun plan(
        desired: List<GoogleEvent>,
        shadows: List<EventShadowRef>,
    ): List<GooglePushAction> {
        val byTaskId = shadows.mapNotNull { shadow ->
            shadow.taskId?.let { it to shadow }
        }.toMap()

        return desired.mapNotNull { want ->
            // An event with no owner is a foreign event the user has not adopted, or a
            // placeholder that lost its task. Either way there is no local intent to write,
            // and matching it on a null key would claim an event that belongs to nobody.
            val taskId = want.taskId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val shadow = byTaskId[taskId]
            when {
                // Known, and the user removed it on purpose. Re-creating it would be the
                // single most confusing thing this feature could do: they cancelled the
                // event, and it came back. Deleting the shadow instead of tombstoning it
                // made this case indistinguishable from a task that had never been synced.
                shadow?.cancelledAt != null -> null

                shadow == null -> GooglePushAction.Insert(TaskId(taskId), want)

                differsFromBase(want, shadow) -> GooglePushAction.Patch(
                    taskId = TaskId(taskId),
                    event = desiredOverShadow(want, shadow),
                    etag = shadow.etag,
                )

                else -> null // Already in agreement; the common case, and writing anyway
                // would bump `updatedAt` on every pass and sync the bump straight back.
            }
        }
    }

    /**
     * Whether [want] disagrees with what the shadow says Google last accepted.
     *
     * Compared against the shadow rather than against Google's current event, because the
     * shadow *is* the agreed ancestor: a difference from it means the task moved since the
     * last time both sides agreed, which is the only thing a push is for.
     */
    private fun differsFromBase(want: GoogleEvent, shadow: EventShadowRef): Boolean {
        val base = shadow.base
        return want.title != base.title ||
            want.description != base.description ||
            want.startsAt != base.startsAt ||
            want.endsAt != base.endsAt ||
            (want.allDay ?: false) != (base.allDay ?: false)
    }

    /**
     * The event to send: the task's own fields, and Google's for the rest.
     *
     * @see GooglePushPlanner for why `location` and `recurrenceRule` come from the shadow.
     */
    fun desiredOverShadow(want: GoogleEvent, shadow: EventShadowRef): GoogleEvent {
        val base = shadow.base
        return want.copy(
            id = GoogleEventId(shadow.eventId),
            calendarId = shadow.calendarId,
            // Google's fields, preserved verbatim. The RRULE in particular must round-trip
            // byte-identical: the app has no RFC 5545 parser, so re-sending a rewritten
            // rule would change how many occurrences a series has.
            location = base.location,
            recurrenceRule = base.recurrenceRule,
            etag = shadow.etag,
        )
    }

    /** The app-owned field names, for tests and for the day someone adds a seventh. */
    fun appOwnedFields(): List<String> = APP_OWNED_FIELDS
}
