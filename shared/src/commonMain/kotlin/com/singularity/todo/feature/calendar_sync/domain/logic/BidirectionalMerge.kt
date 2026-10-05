package com.singularity.todo.feature.calendar_sync.domain.logic

import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncEvent
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEvent
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventId
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventStatus
import kotlin.time.Instant

/**
 * The per-field values that make a 3-way merge possible: what we last agreed both sides
 * said.
 *
 * ## Why a shadow and not a checksum
 *
 * The device-calendar path stores a single `checksum: Int` over the whole event, which is
 * enough to answer "did anything change?" but not "what changed?". A merge needs the third
 * input — the common ancestor — per field. Every field is nullable so that *absent* is
 * representable and distinguishable from *present and empty*: a Google event with no
 * description and a task with an empty one are different states, and collapsing them would
 * make the merge invent a change that never happened.
 *
 * Nullable fields also make a missing shadow honest: [emptyShadow] has every field null, so
 * a row with no recorded base is treated as "we know nothing", which
 * [BidirectionalMerge] refuses to silently push over.
 */
data class EventShadow(
    val title: String? = null,
    val description: String? = null,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    val allDay: Boolean? = null,
    val location: String? = null,
    /**
     * Google's own RRULE string, stored verbatim.
     *
     * Never parsed, never regenerated — see [GoogleEvent.recurrenceRule] for why.
     */
    val recurrenceRule: String? = null,
) {
    /**
     * True when at least one field was actually recorded.
     *
     * On the instance rather than the companion, because [BidirectionalMerge] asks this of
     * the base it was handed. An all-null shadow means "we never synced this", which is
     * not the same as "every field is genuinely absent" — and the merge must not push over
     * a remote edit when it has no ancestor to compare against.
     */
    val isKnown: Boolean
        get() = this != EventShadow()

    companion object {
        /** The shadow for an event we have never synced: nothing is known. */
        fun emptyShadow(): EventShadow = EventShadow()
    }
}

/**
 * Per-field outcome of a 3-way merge.
 *
 * [NoOp] is distinct from "both sides agree and nothing to do" vs "both sides converge on
 * the same value" only in reporting — both mean the caller has nothing to write.
 */
sealed interface FieldOutcome {
    /** Neither side moved away from the base. */
    data object NoOp : FieldOutcome

    /** Only our side changed — write it out to the provider. */
    data class Push(val value: Any?) : FieldOutcome

    /** Only the provider changed — apply it to the local task. */
    data class ApplyToTask(val value: Any?) : FieldOutcome

    /**
     * Both sides changed the same field to different values, within the same sync window.
     *
     * The app's value is kept and the change is reported, rather than asking the user to
     * choose: the competing edit is almost always the same person minutes later, and a
     * dialog for "I renamed this on my laptop" is noise at the moment they are trying to
     * get work done.
     */
    data class Conflict(val ours: Any?, val theirs: Any?) : FieldOutcome
}

/**
 * The result of merging one task against one provider event.
 */
data class MergeResult(
    val eventId: GoogleEventId,
    /** Per-field decisions, in the declaration order of [EventShadow]. */
    val fields: List<FieldOutcome>,
    /** Set when at least one field is a [FieldOutcome.Conflict]. */
    val hasConflict: Boolean,
) {
    /** The outcomes that must be written out to the provider. */
    val pushes: List<FieldOutcome.Push> get() = fields.filterIsInstance<FieldOutcome.Push>()

    /** The outcomes that must be applied to the local task. */
    val applies: List<FieldOutcome.ApplyToTask> get() = fields.filterIsInstance<FieldOutcome.ApplyToTask>()

    /*
     * The named per-field accessors below.
     *
     * `fields` is positional: index 0 is title, 1 description, 2 startsAt, 3 endsAt, 4
     * allDay, 5 location, 6 recurrence. [EventShadow] declares title, description, startsAt,
     * endsAt, allDay, location, recurrence. Those indices are bound to that declaration
     * order, which is the order [BidirectionalMerge] emits them in, and they must be changed
     * together with it — a field added to one list and not the other would silently relabel
     * every outcome after it (a rename would start being read as a time change), with no
     * compile error and no failing test. Code outside this file reads the outcomes through
     * these accessors, never through a raw index.
     *
     * A short list is treated as NoOp rather than as a crash: the caller asking about a
     * field it cannot see is better answered "nothing to do here" than an exception from a
     * getter.
     */

    /** The outcome for the title, or [FieldOutcome.NoOp] when absent. */
    val title: FieldOutcome get() = fields.getOrElse(0) { FieldOutcome.NoOp }

    /** The outcome for the description, or [FieldOutcome.NoOp] when absent. */
    val description: FieldOutcome get() = fields.getOrElse(1) { FieldOutcome.NoOp }

    /** The outcome for the start time, or [FieldOutcome.NoOp] when absent. */
    val startsAt: FieldOutcome get() = fields.getOrElse(2) { FieldOutcome.NoOp }

    /** The outcome for the end time, or [FieldOutcome.NoOp] when absent. */
    val endsAt: FieldOutcome get() = fields.getOrElse(3) { FieldOutcome.NoOp }

    /** The outcome for the all-day flag, or [FieldOutcome.NoOp] when absent. */
    val allDay: FieldOutcome get() = fields.getOrElse(4) { FieldOutcome.NoOp }

    /** The outcome for the location, or [FieldOutcome.NoOp] when absent. */
    val location: FieldOutcome get() = fields.getOrElse(5) { FieldOutcome.NoOp }

    /** The outcome for the repeat rule, or [FieldOutcome.NoOp] when absent. */
    val recurrence: FieldOutcome get() = fields.getOrElse(6) { FieldOutcome.NoOp }

    /**
     * True when this merge requires attention before it can be written.
     *
     * Only the repeat rule can produce this on a *known* base. Every other overlapping
     * field resolves to the app's value, which is safe: a task title has no meaningful
     * union of two intents. A repeat *series* does — a local regeneration changes how many
     * occurrences exist, so pushing it over a concurrent Google-side series change can
     * quietly add or remove events from the user's calendar.
     *
     * On an *unknown* base every disagreeing field is a conflict, and the caller should
     * treat this as "this event has no recorded ancestor" rather than "the user has a
     * choice to make" — see [BidirectionalMerge].
     */
    val requiresAttention: Boolean
        get() = hasConflict && conflictedField != null

    /** Names the field that needs a human, or null when the conflicts were all safe. */
    val conflictedField: String?
        get() = if (requiresRecurrenceConflict) "recurrenceRule" else null

    private val requiresRecurrenceConflict: Boolean
        get() = recurrence is FieldOutcome.Conflict
}

/**
 * Three-way merge between the local task, the provider event, and the last agreed shadow.
 *
 * ## The rule, in full
 *
 * For each field, with `ours` = local, `theirs` = provider, `base` = shadow:
 *
 * - `ours == theirs` → [FieldOutcome.NoOp]. The sides converged; nothing to write. This is
 *   checked *first* so that a field both sides changed to the same value is not reported as
 *   a conflict.
 * - neither changed → [FieldOutcome.NoOp]
 * - only ours changed → [FieldOutcome.Push]
 * - only theirs changed → [FieldOutcome.ApplyToTask]
 * - both changed differently → [FieldOutcome.Conflict], resolved in our favour
 *
 * ## Why there is no clock here
 *
 * The function reads no time and does no I/O, so every branch is reachable from a test and
 * the same input always produces the same output. Ordering and staleness are decided by the
 * caller, which owns the cursors and decides what counts as "the same sync window".
 *
 * ## Why an unknown base refuses to push
 *
 * With no recorded shadow there is no common ancestor, so "only ours changed" cannot be
 * established — we cannot tell our change from theirs. Guessing would let an unmarked
 * provider edit be overwritten on first sync. The caller decides what to do with a
 * [FieldOutcome.Conflict] it did not expect; this function's job is to not be wrong.
 */
object BidirectionalMerge {

    /**
     * Merges [ours] and [theirs] against [base].
     *
     * Pure: no clock, no I/O, no logging. [remoteUpdatedAtMs] is not consulted here — the
     * caller uses it to decide *which* value wins before calling, and this function only
     * reports that the two sides disagree.
     */
    fun merge(ours: GoogleEvent, theirs: GoogleEvent, base: EventShadow): MergeResult {
        val baseKnown = base.isKnown
        val fields = listOf(
            decide(ours.title, theirs.title, base.title, baseKnown),
            decide(ours.description, theirs.description, base.description, baseKnown),
            decide(ours.startsAt, theirs.startsAt, base.startsAt, baseKnown),
            decide(ours.endsAt, theirs.endsAt, base.endsAt, baseKnown),
            decide(ours.allDay, theirs.allDay, base.allDay, baseKnown),
            decide(ours.location, theirs.location, base.location, baseKnown),
            decide(ours.recurrenceRule, theirs.recurrenceRule, base.recurrenceRule, baseKnown),
        )
        return MergeResult(
            eventId = theirs.id,
            fields = fields,
            hasConflict = fields.any { it is FieldOutcome.Conflict },
        )
    }

    private inline fun <T> decide(ours: T, theirs: T, base: T?, baseKnown: Boolean): FieldOutcome {
        // Converged, whatever the base said. Checked first on purpose: two sides that
        // arrived at the same value have not disagreed, and calling that a conflict would
        // report a problem where there is none.
        if (ours == theirs) return FieldOutcome.NoOp

        // `baseKnown` is passed in rather than read off `base`, because `base` here is the
        // one *field's* value and carries no information about whether the shadow as a
        // whole was ever recorded.
        val localChanged = baseKnown && ours != base
        val remoteChanged = baseKnown && theirs != base

        return when {
            // No common ancestor, so "only one side moved" cannot be established — and
            // neither can "both did". Reported as a conflict rather than defaulted to
            // NoOp: a silent no-op would leave the event permanently unsynced with nothing
            // to show for it, which is worse than an explicit "cannot tell" the caller
            // can act on (typically by writing a shadow and re-merging).
            !baseKnown -> FieldOutcome.Conflict(ours, theirs)

            !localChanged && !remoteChanged -> FieldOutcome.NoOp

            localChanged && !remoteChanged -> FieldOutcome.Push(ours)

            !localChanged && remoteChanged -> FieldOutcome.ApplyToTask(theirs)

            // Both moved, and to different values. Reported, not silently resolved: for
            // every field but the repeat rule the caller resolves in our favour, and for
            // the repeat rule the caller holds the write back entirely.
            else -> FieldOutcome.Conflict(ours, theirs)
        }
    }
}

/** The local task rendered as the provider event we would like to see. */
fun CalendarSyncEvent.toGoogleEvent(id: GoogleEventId): GoogleEvent = GoogleEvent(
    id = id,
    calendarId = calendarId,
    title = title,
    description = description,
    startsAt = Instant.fromEpochMilliseconds(startMs),
    endsAt = Instant.fromEpochMilliseconds(endMs),
    allDay = allDay,
    location = null,
    recurrenceRule = null,
    status = GoogleEventStatus.Confirmed,
    etag = null,
    updatedAt = null,
    taskId = taskId.value,
)

/** The shadow for a state we have just written to the provider. */
fun GoogleEvent.toShadow(): EventShadow = EventShadow(
    title = title,
    description = description,
    startsAt = startsAt,
    endsAt = endsAt,
    allDay = allDay,
    location = location,
    recurrenceRule = recurrenceRule,
)
