package com.singularity.todo.feature.calendar_sync.domain.logic

import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEvent
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventId
import kotlin.time.Instant
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The merge is the piece most likely to be "simplified" into being wrong, so it is tested
 * as a table rather than by example: every branch of the decision, plus the cases that
 * only show up when a field is absent.
 */
@Tag("fast")
class BidirectionalMergeTest {

    private val eventId = GoogleEventId("evt_1")
    private val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val t1 = Instant.fromEpochMilliseconds(1_700_003_600_000)

    private fun event(
        title: String? = "base title",
        description: String? = null,
        startsAt: Instant? = t0,
        endsAt: Instant? = t1,
        allDay: Boolean? = false,
        location: String? = null,
        recurrenceRule: String? = null,
    ) = GoogleEvent(
        id = eventId,
        calendarId = "cal1",
        title = title,
        description = description,
        startsAt = startsAt,
        endsAt = endsAt,
        allDay = allDay,
        location = location,
        recurrenceRule = recurrenceRule,
    )

    private fun shadow(
        title: String? = "base title",
        description: String? = null,
        startsAt: Instant? = t0,
        endsAt: Instant? = t1,
        allDay: Boolean? = false,
        location: String? = null,
        recurrenceRule: String? = null,
    ) = EventShadow(title, description, startsAt, endsAt, allDay, location, recurrenceRule)

    // Field positions in the ordered list `BidirectionalMerge.merge` returns, which
    // follows `EventShadow`'s declaration order. Named rather than bare numbers so a
    // reordering shows up as a compile error instead of a silently wrong assertion.
    private companion object {
        const val TITLE = 0
        const val DESCRIPTION = 1
        const val STARTS_AT = 2
        const val ALL_DAY = 4
        const val RECURRENCE = 6
    }

    // ─── Neither side changed ───────────────────────────────────────────

    @Test
    fun `nothing changed anywhere is a no-op`() {
        val result = BidirectionalMerge.merge(event(), event(), shadow())
        assertTrue(result.fields.all { it is FieldOutcome.NoOp }, "got ${result.fields}")
        assertFalse(result.hasConflict)
    }

    // ─── Only one side changed ──────────────────────────────────────────

    @Test
    fun `a local-only change is pushed`() {
        val result = BidirectionalMerge.merge(event(title = "new title"), event(), shadow())
        assertEquals(FieldOutcome.Push("new title"), result.fields[TITLE])
        assertTrue(result.applies.isEmpty())
    }

    @Test
    fun `a remote-only change is applied to the task`() {
        val result = BidirectionalMerge.merge(event(), event(title = "their title"), shadow())
        assertEquals(FieldOutcome.ApplyToTask("their title"), result.fields[TITLE])
        assertTrue(result.pushes.isEmpty())
    }

    /**
     * The case the whole design exists for: a rename here and a reschedule there are
     * independent, and a last-write-wins implementation destroys one of them.
     */
    @Test
    fun `edits to different fields both survive`() {
        val ours = event(title = "renamed here", startsAt = t0)
        val theirs = event(title = "base title", startsAt = t1)
        val result = BidirectionalMerge.merge(ours, theirs, shadow())

        assertEquals(FieldOutcome.Push("renamed here"), result.fields[TITLE])
        assertEquals(FieldOutcome.ApplyToTask(t1), result.fields[STARTS_AT])
        assertFalse(result.hasConflict, "disjoint fields must not be reported as a conflict")
    }

    // ─── Both changed ───────────────────────────────────────────────────

    @Test
    fun `both sides converging on the same value is a no-op, not a conflict`() {
        val result = BidirectionalMerge.merge(event(title = "same"), event(title = "same"), shadow())
        assertEquals(FieldOutcome.NoOp, result.fields[TITLE])
        assertFalse(result.hasConflict)
    }

    @Test
    fun `both changed to different values is reported as a conflict`() {
        val result = BidirectionalMerge.merge(event(title = "ours"), event(title = "theirs"), shadow())
        assertEquals(FieldOutcome.Conflict("ours", "theirs"), result.fields[TITLE])
        assertTrue(result.hasConflict)
    }

    @Test
    fun `a title conflict is resolved in the app's favour without user input`() {
        val result = BidirectionalMerge.merge(event(title = "ours"), event(title = "theirs"), shadow())
        // The local value is the one the caller writes, and no merge UI is required.
        assertNull(result.conflictedField, "an ordinary field must not demand a decision")
        assertFalse(result.requiresAttention)
    }

    @Test
    fun `a repeat rule changed on both sides is held back for a human`() {
        val base = "FREQ=WEEKLY;BYDAY=MO,WE"
        val result = BidirectionalMerge.merge(
            event(recurrenceRule = "FREQ=WEEKLY;BYDAY=TU"),
            event(recurrenceRule = "FREQ=WEEKLY;BYDAY=TH"),
            shadow(recurrenceRule = base),
        )
        assertTrue(result.hasConflict)
        assertTrue(result.requiresAttention, "a series change must not be pushed silently")
        assertEquals("recurrenceRule", result.conflictedField)
    }

    // ─── Absent vs empty ────────────────────────────────────────────────

    /**
     * The reason every shadow field is nullable. A Google event with no description and a
     * task with an empty one are different states; treating them as equal would invent a
     * change that never happened.
     */
    @Test
    fun `absent and empty are different states`() {
        val base = shadow(description = null)
        val result = BidirectionalMerge.merge(
            event(description = ""),
            event(description = null),
            base,
        )
        assertEquals(FieldOutcome.Push(""), result.fields[DESCRIPTION])
    }

    /**
     * The remote dropped the description while the task still has it — the local value is
     * unchanged, so the only thing to do is bring the task in line.
     *
     * (The local side must *keep* the base value here. If both sides already read `null`
     * they have converged, and the merge correctly does nothing — which is what makes an
     * absent field distinguishable from an emptied one.)
     */
    @Test
    fun `clearing a remote field is applied to the task`() {
        val result = BidirectionalMerge.merge(
            event(description = "had one"),
            event(description = null),
            shadow(description = "had one"),
        )
        assertEquals(FieldOutcome.ApplyToTask(null), result.fields[DESCRIPTION])
    }

    @Test
    fun `both sides having cleared a field is a no-op, not an apply`() {
        val result = BidirectionalMerge.merge(
            event(description = null),
            event(description = null),
            shadow(description = "had one"),
        )
        assertEquals(FieldOutcome.NoOp, result.fields[DESCRIPTION])
    }

    // ─── Unknown base ───────────────────────────────────────────────────

    @Test
    fun `an empty shadow reports itself as unknown`() {
        assertFalse(EventShadow.emptyShadow().isKnown)
        assertTrue(shadow().isKnown)
    }

    /**
     * With no common ancestor, "only ours changed" cannot be established. The merge must
     * not guess and push over a remote edit it knows nothing about.
     */
    @Test
    fun `an unknown base never produces a push`() {
        val result = BidirectionalMerge.merge(
            event(title = "ours"),
            event(title = "theirs"),
            EventShadow.emptyShadow(),
        )
        assertTrue(result.pushes.isEmpty(), "an unknown base must not justify a write: ${result.fields}")
        assertTrue(result.hasConflict)
    }

    @Test
    fun `an unknown base never produces an apply either`() {
        val result = BidirectionalMerge.merge(event(title = "ours"), event(), EventShadow.emptyShadow())
        assertTrue(result.applies.isEmpty(), "got ${result.fields}")
    }

    // ─── Mixed field outcomes ───────────────────────────────────────────

    @Test
    fun `a mixed set of outcomes is reported per field`() {
        val result = BidirectionalMerge.merge(
            event(title = "pushed", allDay = true, description = null),
            event(title = "base title", allDay = false, description = "from google"),
            shadow(),
        )
        assertEquals(FieldOutcome.Push("pushed"), result.fields[TITLE])
        assertEquals(FieldOutcome.Push(true), result.fields[ALL_DAY])
        assertEquals(FieldOutcome.ApplyToTask("from google"), result.fields[DESCRIPTION])
    }

    // ─── Purity ─────────────────────────────────────────────────────────

    @Test
    fun `the merge is deterministic`() {
        val ours = event(title = "ours", startsAt = t0)
        val theirs = event(title = "theirs", startsAt = t1)
        val first = BidirectionalMerge.merge(ours, theirs, shadow())
        val second = BidirectionalMerge.merge(ours, theirs, shadow())
        assertEquals(first.fields, second.fields)
    }

    @Test
    fun `the result identifies the event it belongs to`() {
        val result = BidirectionalMerge.merge(event(), event(), shadow())
        assertEquals(eventId, result.eventId)
    }

    // ─── Shadow round trip ──────────────────────────────────────────────

    @Test
    fun `a shadow taken from an event reproduces no changes on the next merge`() {
        val written = event(
            title = "final",
            description = "desc",
            location = "office",
            recurrenceRule = "FREQ=WEEKLY;INTERVAL=2;COUNT=10;UNTIL=20270101T000000Z",
        )
        val shadow = written.toShadow()

        // Same event on both sides, with the shadow recorded: nothing to do.
        val result = BidirectionalMerge.merge(written, written, shadow)
        assertTrue(result.fields.all { it is FieldOutcome.NoOp }, "got ${result.fields}")
    }

    /**
     * The test that would catch a silent series rewrite. A rule the app cannot represent
     * must survive untouched, because the app has no RFC 5545 implementation to re-emit it
     * from.
     */
    @Test
    fun `a rule the app cannot express survives a round trip byte-identical`() {
        val exotic = "FREQ=MONTHLY;INTERVAL=2;BYDAY=1FR;UNTIL=20270101T000000Z;WKST=SU"
        val event = event(recurrenceRule = exotic)
        val shadow = event.toShadow()
        val result = BidirectionalMerge.merge(event, event, shadow)
        assertEquals(FieldOutcome.NoOp, result.fields[RECURRENCE])
        assertEquals(exotic, event.toShadow().recurrenceRule)
    }
}
