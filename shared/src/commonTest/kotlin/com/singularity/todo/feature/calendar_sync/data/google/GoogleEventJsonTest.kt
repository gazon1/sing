package com.singularity.todo.feature.calendar_sync.data.google

import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEvent
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventId
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Google's payload shape, tested as literal JSON.
 *
 * No transport: a wire format becomes testable against a canned body, and the awkward
 * cases — an all-day event, a cancelled one, a cleared field — are exactly the ones that
 * are hard to reproduce by hand against a live account.
 */
@Tag("fast")
class GoogleEventJsonTest {

    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun event(json: String) = parse(json).toGoogleEventOrNull()

    @Test
    fun `a timed event is read`() {
        val parsed = event(
            """
            {
              "id": "evt_1", "status": "confirmed", "etag": "\"abc\"",
              "summary": "Standup", "description": "daily", "location": "Room 2",
              "start": {"dateTime": "2026-10-05T09:00:00Z"},
              "end":   {"dateTime": "2026-10-05T09:15:00Z"},
              "updated": "2026-10-05T08:00:00Z"
            }
            """.trimIndent(),
        )

        assertNotNull(parsed)
        assertEquals(GoogleEventId("evt_1"), parsed.id)
        assertEquals("Standup", parsed.title)
        assertEquals("daily", parsed.description)
        assertEquals("Room 2", parsed.location)
        assertEquals(Instant.parse("2026-10-05T09:00:00Z"), parsed.startsAt)
        assertEquals(Instant.parse("2026-10-05T09:15:00Z"), parsed.endsAt)
        assertEquals("\"abc\"", parsed.etag)
        assertEquals(GoogleEventStatus.Confirmed, parsed.status)
        assertEquals(false, parsed.allDay)
    }

    /**
     * An all-day event carries `date`, not `dateTime`. Reading only `dateTime` would drop
     * every all-day event the user has — birthdays, holidays, a day off — with no error.
     */
    @Test
    fun `an all-day event is read from its date, not its dateTime`() {
        val parsed = event(
            """
            {
              "id": "evt_2", "status": "confirmed", "summary": "Dentist",
              "start": {"date": "2026-10-06"},
              "end":   {"date": "2026-10-07"}
            }
            """.trimIndent(),
        )

        assertNotNull(parsed)
        assertEquals(true, parsed.allDay)
        assertNotNull(parsed.startsAt, "an all-day event still has a start")
        assertEquals("2026-10-06", parsed.startsAt.toString().substring(0, 10))
    }

    /**
     * Cancelled is not deleted. A cancelled event still arrives in an incremental listing,
     * and reading it as "gone" makes a sync drop the mapping and then re-create the event.
     */
    @Test
    fun `a cancelled event is read as cancelled, not as absent`() {
        val parsed = event("""{"id": "evt_3", "status": "cancelled", "summary": "Old"}""")

        assertNotNull(parsed)
        assertEquals(GoogleEventStatus.Cancelled, parsed.status)
        assertTrue(!parsed.isLive)
    }

    @Test
    fun `a tentative event is read as tentative`() {
        assertEquals(
            GoogleEventStatus.Tentative,
            event("""{"id": "evt_4", "status": "tentative"}""")?.status,
        )
    }

    @Test
    fun `an unknown status falls back to confirmed rather than throwing`() {
        assertEquals(
            GoogleEventStatus.Confirmed,
            event("""{"id": "evt_5", "status": "somethingNew"}""")?.status,
        )
    }

    /** An event with no id cannot be updated, cancelled, or correlated — so it is dropped. */
    @Test
    fun `an item with no id is dropped rather than half-represented`() {
        assertNull(event("""{"summary": "No id here"}"""))
    }

    @Test
    fun `the task id is read from the private extended property`() {
        val parsed = event(
            """
            {"id": "evt_6", "extendedProperties": {"private": {"$TASK_ID_PROPERTY": "task-1"}}}
            """.trimIndent(),
        )
        assertEquals("task-1", parsed?.taskId)
    }

    @Test
    fun `an event with no marker has no task id, which is what makes it foreign`() {
        val parsed = event("""{"id": "evt_7", "summary": "Made in the Google app"}""")
        assertNull(parsed?.taskId, "a null taskId is how a foreign event is recognised")
    }

    @Test
    fun `the repeat rule is carried through verbatim`() {
        val rule = "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;UNTIL=20270101T000000Z"
        val parsed = event("""{"id": "evt_8", "recurrence": ["RRULE:$rule"]}""")
        assertEquals(rule, parsed?.recurrenceRule, "the app has no RFC 5545 parser; it must not touch this")
    }

    /**
     * `recurrence` is a list of prefixed strings. Reading it as an object returns null, and a
     * null rule reads as "does not repeat" — so a weekly series would be flattened into one
     * occurrence with nothing reporting an error.
     */
    @Test
    fun `a rule is read out of the recurrence array, not as an object`() {
        val rule = "FREQ=DAILY"
        assertEquals(rule, event("""{"id": "e1", "recurrence": ["RRULE:$rule"]}""")?.recurrenceRule)
    }

    /** Google sends other lines in the same array; only the rule is ours to read. */
    @Test
    fun `exception dates in the array do not break the rule read`() {
        val rule = "FREQ=WEEKLY;BYDAY=MO"
        val parsed = event(
            """{"id": "e2", "recurrence": ["RRULE:$rule", "EXDATE;TZID=UTC:20261012T000000Z"]}""",
        )
        assertEquals(rule, parsed?.recurrenceRule)
    }

    @Test
    fun `an event with an empty recurrence array has no rule`() {
        assertNull(event("""{"id": "e3", "recurrence": []}""")?.recurrenceRule)
    }

    // ─── Request building ───────────────────────────────────────────────

    private fun requestBody(event: GoogleEvent): JsonObject =
        Json.parseToJsonElement(event.toRequestJson()).jsonObject

    private fun baseEvent(
        title: String? = "Standup",
        description: String? = "daily",
        location: String? = null,
        recurrenceRule: String? = null,
        taskId: String? = "task-1",
        allDay: Boolean? = false,
    ) = GoogleEvent(
        id = GoogleEventId("evt_1"),
        calendarId = "primary",
        title = title,
        description = description,
        startsAt = Instant.parse("2026-10-05T09:00:00Z"),
        endsAt = Instant.parse("2026-10-05T09:15:00Z"),
        allDay = allDay,
        location = location,
        recurrenceRule = recurrenceRule,
        taskId = taskId,
    )

    @Test
    fun `a request carries the fields this app owns`() {
        val body = requestBody(baseEvent())

        assertEquals("Standup", body["summary"]?.jsonPrimitive?.content)
        assertEquals("daily", body["description"]?.jsonPrimitive?.content)
        assertEquals("2026-10-05T09:00:00Z", body["start"]?.jsonObject?.get("dateTime")?.jsonPrimitive?.content)
    }

    /**
     * A cleared field has to be sent as an explicit null. Omitting it leaves the old value
     * on Google's side, so a user deleting a description would watch it reappear.
     */
    @Test
    fun `a cleared field is sent as an explicit null, not omitted`() {
        val body = requestBody(baseEvent(title = null, description = null, location = null))

        assertTrue("summary" in body, "a cleared title must be present, so it can be nulled")
        assertTrue(body["summary"] is kotlinx.serialization.json.JsonNull)
        assertTrue(body["description"] is kotlinx.serialization.json.JsonNull)
        assertTrue(body["location"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `an all-day event is sent as a date, not a dateTime`() {
        val body = requestBody(baseEvent(allDay = true))
        val start = body["start"]?.jsonObject
        assertNotNull(start?.get("date"))
        assertNull(start["dateTime"], "an all-day event must not carry a time")
    }

    @Test
    fun `the task marker is written so the next pull can recognise the event as ours`() {
        val body = requestBody(baseEvent(taskId = "task-42"))
        val private = body["extendedProperties"]?.jsonObject?.get("private")?.jsonObject
        assertEquals("task-42", private?.get(TASK_ID_PROPERTY)?.jsonPrimitive?.content)
    }

    @Test
    fun `a repeat rule is written back unchanged`() {
        val rule = "FREQ=MONTHLY;BYDAY=1FR;UNTIL=20270101T000000Z"
        val body = requestBody(baseEvent(recurrenceRule = rule))
        val lines = (body["recurrence"] as? kotlinx.serialization.json.JsonArray)
            ?.map { it.jsonPrimitive.content }
            .orEmpty()
        assertEquals(listOf("RRULE:$rule"), lines, "Google expects a prefixed list, not an object")
    }

    /**
     * A round trip through Google's own shape must not drift.
     *
     * The request body is *not* a response — it carries no `id`, because the id is what
     * Google assigns. So the round trip feeds the body back in with the identity Google
     * would have added, rather than pretending the body is already a full event.
     */
    @Test
    fun `an event survives a read-write-read cycle`() {
        val original = parse(
            """
            {"id": "evt_9", "status": "confirmed", "summary": "Standup",
             "description": "daily",
             "start": {"dateTime": "2026-10-05T09:00:00Z"},
             "end":   {"dateTime": "2026-10-05T09:15:00Z"}}
            """.trimIndent(),
        ).toGoogleEventOrNull()

        assertNotNull(original)

        // What Google stores back: the fields we sent, plus the identity it owns. The
        // request body carries no `id` — that is a create, not a response — so the identity
        // Google assigns is added to the body before reading it back.
        val body = requestBody(original)
        val stored = parse(
            buildJsonObject {
                put("id", JsonPrimitive("evt_9"))
                put("status", JsonPrimitive("confirmed"))
                body.forEach { (key, value) -> put(key, value) }
            }.toString(),
        ).toGoogleEventOrNull()

        assertNotNull(stored)
        assertEquals(original.title, stored.title)
        assertEquals(original.description, stored.description)
        assertEquals(original.startsAt, stored.startsAt)
        assertEquals(original.endsAt, stored.endsAt)
    }

    /** The request body legitimately has no id — it is a create, not a response. */
    @Test
    fun `a request body carries no id, because Google assigns it`() {
        val body = requestBody(baseEvent())
        assertTrue("id" !in body, "sending an id on create is wrong; Google owns it")
    }

    @Test
    fun `an unknown field from a newer Google does not break the read`() {
        val parsed = event(
            """{"id": "evt_10", "summary": "Fine", "somethingGoogleAddedLater": {"a": 1}}""",
        )
        assertEquals("Fine", parsed?.title)
    }
}
