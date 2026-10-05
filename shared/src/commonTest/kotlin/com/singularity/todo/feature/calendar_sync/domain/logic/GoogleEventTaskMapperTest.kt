package com.singularity.todo.feature.calendar_sync.domain.logic

import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEvent
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventId
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The Google→task mapping, which is where the two models' mismatches have to be absorbed.
 *
 * The cases that matter are the ones where the two models genuinely disagree: Google has a
 * location and `Task` does not, Google sends a verbatim RRULE and `Task` has a structured
 * `RecurrenceSpec`, and an all-day event has a date but no moment. Each of those is a
 * place where a plausible-looking mapping would quietly lose or invent information.
 */
@Tag("fast")
class GoogleEventTaskMapperTest {

    private val utc = TimeZone.UTC
    private val noon = LocalDateTime(2026, 3, 4, 12, 0).toInstant(utc)
    private val onePm = LocalDateTime(2026, 3, 4, 13, 0).toInstant(utc)

    private fun event(
        id: String = "evt-1",
        title: String? = "Dentist",
        description: String? = null,
        startsAt: Instant? = noon,
        endsAt: Instant? = onePm,
        allDay: Boolean = false,
        location: String? = null,
        recurrenceRule: String? = null,
        status: GoogleEventStatus = GoogleEventStatus.Confirmed,
    ) = GoogleEvent(
        id = GoogleEventId(id),
        calendarId = "primary",
        title = title,
        description = description,
        startsAt = startsAt,
        endsAt = endsAt,
        allDay = allDay,
        location = location,
        recurrenceRule = recurrenceRule,
        status = status,
        etag = "etag-1",
        updatedAt = null,
        taskId = null,
    )

    private fun LocalDateTime(y: Int, m: Int, d: Int, h: Int, min: Int) =
        kotlinx.datetime.LocalDateTime(LocalDate(y, m, d), LocalTime(h, min))

    // ─── draftFor ───────────────────────────────────────────────────────

    @Test
    fun `a timed event becomes a date and a time`() {
        val draft = GoogleEventTaskMapper.draftFor(event(), utc)!!
        assertEquals(LocalDate(2026, 3, 4), draft.dueDate)
        assertEquals(LocalTime(12, 0), draft.dueTime)
        assertEquals(LocalDate(2026, 3, 4), draft.endDate)
        assertEquals(LocalTime(13, 0), draft.endTime)
    }

    @Test
    fun `an all-day event gets a date but no time`() {
        // Midnight would make it sort and remind as a midnight appointment, which is not what
        // "all day" means on a calendar.
        val draft = GoogleEventTaskMapper.draftFor(event(allDay = true), utc)!!
        assertEquals(LocalDate(2026, 3, 4), draft.dueDate)
        assertNull(draft.dueTime)
        assertNull(draft.endTime)
    }

    @Test
    fun `a cancelled event has no draft`() {
        assertNull(GoogleEventTaskMapper.draftFor(event(status = GoogleEventStatus.Cancelled), utc))
    }

    @Test
    fun `an event with no title gets a placeholder rather than failing validation`() {
        // `Task.title` is not nullable, so a blank title would otherwise be a create failure
        // the user cannot act on.
        assertEquals(GoogleEventTaskMapper.UNTITLED, GoogleEventTaskMapper.draftFor(event(title = null), utc)!!.title)
        assertEquals(GoogleEventTaskMapper.UNTITLED, GoogleEventTaskMapper.draftFor(event(title = "   "), utc)!!.title)
    }

    @Test
    fun `blank description and location become null rather than empty strings`() {
        // "absent" and "present but empty" are different states; collapsing them invents a
        // change that never happened.
        val draft = GoogleEventTaskMapper.draftFor(event(description = "", location = ""), utc)!!
        assertNull(draft.description)
    }

    @Test
    fun `an event with no start still becomes a draft`() {
        val draft = GoogleEventTaskMapper.draftFor(event(startsAt = null, endsAt = null), utc)!!
        assertNull(draft.dueDate)
        assertNull(draft.dueTime)
        assertEquals("Dentist", draft.title)
    }

    @Test
    fun `a location is not lost even though no task field carries it`() {
        // `Task` has no location, so the draft cannot hold one — but the value must still
        // survive on the event and into the shadow, because the push path reads location
        // from the shadow. Losing it here would mean the next push blanked the location on a
        // real calendar entry.
        val source = event(location = "Clinic")
        assertEquals("Clinic", source.location)
        val shadow = source.toShadow()
        assertEquals("Clinic", shadow.location)
        assertEquals(EventShadowCodec.encode(source.toShadow()), EventShadowCodec.encode(shadow))
    }

    @Test
    fun `times are read in the given time zone, not in UTC`() {
        val berlin = TimeZone.of("Europe/Berlin")
        val draft = GoogleEventTaskMapper.draftFor(event(), berlin)!!
        // noon UTC is 13:00 in Berlin in March (CET, before the DST switch on the 29th).
        assertEquals(LocalTime(13, 0), draft.dueTime)
    }

    // ─── editsFor ───────────────────────────────────────────────────────

    @Test
    fun `only ApplyToTask outcomes become edits`() {
        val merge = MergeResult(
            eventId = GoogleEventId("evt-1"),
            fields = listOf(
                FieldOutcome.ApplyToTask("Renamed on phone"),
                FieldOutcome.Push("Local title"),
                FieldOutcome.NoOp,
                FieldOutcome.Conflict("ours", "theirs"),
                FieldOutcome.NoOp,
                FieldOutcome.NoOp,
                FieldOutcome.NoOp,
            ),
            hasConflict = true,
        )
        val edits = GoogleEventTaskMapper.editsFor(merge, event(), utc)
        assertEquals(setOf(GoogleEventTaskMapper.TaskField.Title), edits.changed)
        assertEquals("Renamed on phone", edits.title)
    }

    @Test
    fun `a merge with nothing to apply produces no edits`() {
        val merge = MergeResult(
            eventId = GoogleEventId("evt-1"),
            fields = List(7) { FieldOutcome.NoOp },
            hasConflict = false,
        )
        assertTrue(GoogleEventTaskMapper.editsFor(merge, event(), utc).isEmpty)
    }

    @Test
    fun `a remotely cleared field is a change, not an absence`() {
        // The distinction the `changed` set exists for: null here means "clear the
        // description", and a type carrying only nullable values could not say so.
        val merge = MergeResult(
            eventId = GoogleEventId("evt-1"),
            fields = listOf(
                FieldOutcome.NoOp,
                FieldOutcome.ApplyToTask(null),
                FieldOutcome.NoOp,
                FieldOutcome.NoOp,
                FieldOutcome.NoOp,
                FieldOutcome.NoOp,
                FieldOutcome.NoOp,
            ),
            hasConflict = false,
        )
        val edits = GoogleEventTaskMapper.editsFor(merge, event(), utc)
        assertEquals(setOf(GoogleEventTaskMapper.TaskField.Description), edits.changed)
        assertNull(edits.description)
    }

    @Test
    fun `a remote time change is a move`() {
        val merge = MergeResult(
            eventId = GoogleEventId("evt-1"),
            fields = listOf(
                FieldOutcome.NoOp,
                FieldOutcome.NoOp,
                FieldOutcome.ApplyToTask(LocalDateTime(2026, 3, 5, 9, 30).toInstant(utc)),
                FieldOutcome.NoOp,
                FieldOutcome.NoOp,
                FieldOutcome.NoOp,
                FieldOutcome.NoOp,
            ),
            hasConflict = false,
        )
        val edits = GoogleEventTaskMapper.editsFor(merge, event(), utc)
        assertTrue(edits.movesTime)
        assertEquals(LocalDate(2026, 3, 5), edits.dueDate)
        assertEquals(LocalTime(9, 30), edits.dueTime)
    }

    @Test
    fun `a remote title change is not a move`() {
        // A rename must not drag a reminder along with it.
        val merge = MergeResult(
            eventId = GoogleEventId("evt-1"),
            fields = listOf(
                FieldOutcome.ApplyToTask("Shorter"),
                FieldOutcome.NoOp,
                FieldOutcome.NoOp,
                FieldOutcome.NoOp,
                FieldOutcome.NoOp,
                FieldOutcome.NoOp,
                FieldOutcome.NoOp,
            ),
            hasConflict = false,
        )
        assertFalse(GoogleEventTaskMapper.editsFor(merge, event(), utc).movesTime)
    }

    @Test
    fun `a moved all-day event lands on a date with no time`() {
        val merge = MergeResult(
            eventId = GoogleEventId("evt-1"),
            fields = listOf(
                FieldOutcome.NoOp,
                FieldOutcome.NoOp,
                FieldOutcome.ApplyToTask(LocalDateTime(2026, 3, 6, 0, 0).toInstant(utc)),
                FieldOutcome.NoOp,
                FieldOutcome.NoOp,
                FieldOutcome.NoOp,
                FieldOutcome.NoOp,
            ),
            hasConflict = false,
        )
        val edits = GoogleEventTaskMapper.editsFor(merge, event(allDay = true), utc)
        assertEquals(LocalDate(2026, 3, 6), edits.dueDate)
        assertNull(edits.dueTime)
    }

    @Test
    fun `a remote recurrence change never reaches the task`() {
        // Google's RRULE is stored and re-sent verbatim and is never parsed. Setting
        // `Task.recurrence` would mean an RFC 5545 parser, and a lossy one would silently
        // change how many occurrences a series has.
        val merge = MergeResult(
            eventId = GoogleEventId("evt-1"),
            fields = listOf(
                FieldOutcome.NoOp,
                FieldOutcome.NoOp,
                FieldOutcome.NoOp,
                FieldOutcome.NoOp,
                FieldOutcome.NoOp,
                FieldOutcome.NoOp,
                FieldOutcome.ApplyToTask("RRULE:FREQ=WEEKLY"),
            ),
            hasConflict = false,
        )
        assertTrue(GoogleEventTaskMapper.editsFor(merge, event(recurrenceRule = "RRULE:FREQ=WEEKLY"), utc).isEmpty)
    }
}
