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
 * The shadow is the merge's common ancestor, so a lossy round trip does not merely lose
 * data — it changes what the merge *concludes*. These tests therefore assert equality
 * after a round trip, not just that something came back.
 */
@Tag("fast")
class EventShadowCodecTest {

    private val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val t1 = Instant.fromEpochMilliseconds(1_700_003_600_000)

    private fun roundTrip(shadow: EventShadow): EventShadow =
        EventShadowCodec.decodeOrEmpty(EventShadowCodec.encode(shadow))

    @Test
    fun `a fully populated shadow round-trips`() {
        val shadow = EventShadow(
            title = "Dentist",
            description = "bring the card",
            startsAt = t0,
            endsAt = t1,
            allDay = false,
            location = "12 High Street",
            recurrenceRule = "FREQ=WEEKLY;INTERVAL=2;COUNT=10;UNTIL=20270101T000000Z",
        )
        assertEquals(shadow, roundTrip(shadow))
    }

    /**
     * "Never looked" and "looked, and there was nothing" are different facts, and the
     * merge acts on the difference: an unknown base refuses to push, a known-empty one may
     * conclude the user deleted something.
     */
    @Test
    fun `an empty shadow stays known-but-empty after a round trip`() {
        val shadow = EventShadow()
        val decoded = roundTrip(shadow)
        assertEquals(shadow, decoded)
        assertFalse(decoded.isKnown, "an all-absent shadow written and read back is still 'known'")
    }

    @Test
    fun `an unreadable value decodes to an unknown shadow, not an empty one`() {
        // The point is `isKnown == false`: this is "we cannot tell", which must not be
        // confused with "everything is absent".
        listOf(null, "", "   ", "not json", "[1,2,3]", "{\"title\": ").forEach { raw ->
            val decoded = EventShadowCodec.decodeOrEmpty(raw)
            assertFalse(decoded.isKnown, "\"$raw\" decoded to a known shadow: $decoded")
        }
    }

    @Test
    fun `a partial shadow keeps its absent fields absent`() {
        val shadow = EventShadow(title = "only a title")
        val decoded = roundTrip(shadow)
        assertEquals("only a title", decoded.title)
        assertNull(decoded.description)
        assertNull(decoded.startsAt)
        assertNull(decoded.recurrenceRule)
        assertTrue(decoded.isKnown)
    }

    @Test
    fun `a rule the app cannot express survives byte-identical`() {
        // The repository has no RFC 5545 implementation, so a Google rule is the only copy.
        // Any normalisation here would silently change a series the user depends on.
        val exotic = "FREQ=MONTHLY;INTERVAL=2;BYDAY=1FR;UNTIL=20270101T000000Z;WKST=SU"
        val decoded = roundTrip(EventShadow(recurrenceRule = exotic))
        assertEquals(exotic, decoded.recurrenceRule)
    }

    @Test
    fun `text with quotes and separators survives`() {
        val awkward = "a \"quoted\" note, with: colons and \\ backslashes and 日本語"
        assertEquals(awkward, roundTrip(EventShadow(description = awkward)).description)
    }

    @Test
    fun `an all-day event is not confused with a timed one`() {
        val allDay = roundTrip(EventShadow(allDay = true))
        assertEquals(true, allDay.allDay)
        val timed = roundTrip(EventShadow(allDay = false))
        assertEquals(false, timed.allDay)
    }

    @Test
    fun `an empty string is not confused with absent`() {
        val decoded = roundTrip(EventShadow(title = ""))
        assertEquals("", decoded.title, "an emptied title must not read as absent")
    }

    @Test
    fun `the shadow of a written event is a fixed point`() {
        val event = GoogleEvent(
            id = GoogleEventId("evt_1"),
            calendarId = "cal1",
            title = "Standup",
            description = null,
            startsAt = t0,
            endsAt = t1,
            allDay = false,
            location = "Room 2",
            recurrenceRule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
        )
        val once = EventShadowCodec.shadowOf(event)
        val twice = EventShadowCodec.decodeOrEmpty(EventShadowCodec.encode(once))
        assertEquals(once, twice, "reading back a written event's shadow must not drift")
    }
}
