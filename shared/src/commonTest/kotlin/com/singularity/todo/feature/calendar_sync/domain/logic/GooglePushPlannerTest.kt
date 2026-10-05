package com.singularity.todo.feature.calendar_sync.domain.logic

import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEvent
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventId
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventStatus
import com.singularity.todo.feature.tasks.domain.model.TaskId
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The push planner: which local tasks Google does not yet agree with.
 *
 * Every case here is a way the feature can *look* two-way and not be. A planner that only
 * ever emits inserts never updates; one that sends the whole task over blanks the location
 * and flattens the recurrence on a real calendar entry. Both are silent — no exception, no
 * failed request, just a user's calendar quietly losing fields.
 */
@Tag("fast")
class GooglePushPlannerTest {

    private val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val noon = Instant.fromEpochMilliseconds(1_700_003_600_000)
    private val onePm = Instant.fromEpochMilliseconds(1_700_007_200_000)

    private fun desired(
        taskId: String? = "task-1",
        title: String? = "Buy milk",
        startsAt: Instant? = noon,
        allDay: Boolean = false,
    ) = GoogleEvent(
        id = GoogleEventId("__none__"),
        calendarId = "primary",
        title = title,
        description = null,
        startsAt = startsAt,
        endsAt = onePm,
        allDay = allDay,
        location = null,
        recurrenceRule = null,
        status = GoogleEventStatus.Confirmed,
        etag = null,
        updatedAt = null,
        taskId = taskId,
    )

    private fun shadow(
        eventId: String = "evt-1",
        taskId: String? = "task-1",
        title: String? = "Buy milk",
        startsAt: Instant? = noon,
        location: String? = null,
        recurrenceRule: String? = null,
        etag: String? = "etag-1",
    ) = EventShadowRef(
        eventId = eventId,
        taskId = taskId,
        calendarId = "primary",
        etag = etag,
        base = EventShadow(
            title = title,
            description = null,
            startsAt = startsAt,
            endsAt = onePm,
            allDay = false,
            location = location,
            recurrenceRule = recurrenceRule,
        ),
    )

    // ─── Insert ─────────────────────────────────────────────────────────

    @Test
    fun `a task with no Google event becomes an insert`() {
        val plan = GooglePushPlanner.plan(listOf(desired()), shadows = emptyList())

        val action = plan.single() as GooglePushAction.Insert
        assertEquals(TaskId("task-1"), action.taskId)
    }

    @Test
    fun `an existing event that already agrees produces nothing`() {
        // Writing anyway would bump `updatedAt` on every pass and then sync that bump
        // straight back out — a permanent phantom edit.
        val plan = GooglePushPlanner.plan(listOf(desired()), listOf(shadow()))

        assertTrue(plan.isEmpty(), "an unchanged task must not be written")
    }

    // ─── Patch ──────────────────────────────────────────────────────────

    @Test
    fun `a renamed task produces a patch`() {
        val plan = GooglePushPlanner.plan(listOf(desired(title = "Buy oat milk")), listOf(shadow()))

        val action = plan.single() as GooglePushAction.Patch
        assertEquals("Buy oat milk", action.event.title)
    }

    @Test
    fun `a rescheduled task produces a patch`() {
        val moved = Instant.fromEpochMilliseconds(noon.toEpochMilliseconds() + 86_400_000)

        val plan = GooglePushPlanner.plan(listOf(desired(startsAt = moved)), listOf(shadow()))

        val action = plan.single() as GooglePushAction.Patch
        assertEquals(moved, action.event.startsAt)
    }

    @Test
    fun `a patch carries the etag it must present`() {
        // Without it the write is unguarded, and a concurrent edit on another device is
        // overwritten silently instead of producing a re-plan.
        val plan = GooglePushPlanner.plan(
            listOf(desired(title = "x")),
            listOf(shadow(etag = "etag-42")),
        )

        assertEquals("etag-42", (plan.single() as GooglePushAction.Patch).etag)
    }

    @Test
    fun `a patch keeps the event id Google already assigned`() {
        val plan = GooglePushPlanner.plan(listOf(desired(title = "x")), listOf(shadow(eventId = "evt-77")))

        val action = plan.single() as GooglePushAction.Patch
        assertEquals(GoogleEventId("evt-77"), action.event.id)
    }

    // ─── What a patch must NOT carry ────────────────────────────────────

    @Test
    fun `a patch never blanks a location the app cannot model`() {
        // `Task` has no location field, so a task-derived payload always has `location = null`.
        // Sending that would erase the address on a real calendar entry.
        val plan = GooglePushPlanner.plan(
            listOf(desired(title = "Dentist")),
            listOf(shadow(title = "Dentist old", location = "Clinic")),
        )

        val action = plan.single() as GooglePushAction.Patch
        assertEquals("Clinic", action.event.location)
    }

    @Test
    fun `a patch never rewrites the recurrence rule`() {
        // The app has no RFC 5545 parser. Re-sending a rewritten rule would change how many
        // occurrences a series has — silently, on the user's real calendar.
        val plan = GooglePushPlanner.plan(
            listOf(desired(title = "Standup")),
            listOf(shadow(title = "Standup old", recurrenceRule = "RRULE:FREQ=WEEKLY;BYDAY=MO")),
        )

        val action = plan.single() as GooglePushAction.Patch
        assertEquals("RRULE:FREQ=WEEKLY;BYDAY=MO", action.event.recurrenceRule)
    }

    @Test
    fun `an insert carries no location or recurrence, because the task has neither`() {
        val plan = GooglePushPlanner.plan(listOf(desired()), emptyList())

        val action = plan.single() as GooglePushAction.Insert
        assertNull(action.event.location)
        assertNull(action.event.recurrenceRule)
    }

    // ─── Matching ───────────────────────────────────────────────────────

    @Test
    fun `a shadow with no task is not matched to anything`() {
        // A foreign event the user has not adopted. Treating its null `taskId` as a match
        // would let an unrelated task claim it and overwrite someone else's event.
        val plan = GooglePushPlanner.plan(listOf(desired()), listOf(shadow(taskId = null)))

        assertEquals(1, plan.size)
        assertTrue(plan.single() is GooglePushAction.Insert)
    }

    @Test
    fun `a desired event with no task id is skipped rather than matched`() {
        // Nothing local owns it, so there is nothing to insert and nothing to patch. Matching
        // it anyway would claim an event on the user's calendar that no task corresponds to.
        val plan = GooglePushPlanner.plan(listOf(desired(taskId = null)), listOf(shadow()))

        assertTrue(plan.isEmpty(), "an unowned event must not be written")
    }

    @Test
    fun `tasks are matched by task id, not by the placeholder event id`() {
        // Every desired event carries the same placeholder id. Matching on it would collapse
        // a whole list of tasks onto one event.
        val plan = GooglePushPlanner.plan(
            listOf(desired(taskId = "task-1"), desired(taskId = "task-2")),
            listOf(shadow(eventId = "evt-1", taskId = "task-1")),
        )

        assertEquals(1, plan.size)
        assertEquals(TaskId("task-2"), plan.single().let { (it as GooglePushAction.Insert).taskId })
    }
}
