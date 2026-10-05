package com.singularity.todo.feature.calendar_sync.sync

import com.singularity.todo.core.ids.UlidIdGenerator
import com.singularity.todo.feature.calendar_sync.data.GoogleEventShadowEntity
import com.singularity.todo.feature.calendar_sync.domain.logic.EventShadow
import com.singularity.todo.feature.calendar_sync.domain.logic.EventShadowCodec
import com.singularity.todo.feature.calendar_sync.domain.logic.EventShadowRef
import com.singularity.todo.feature.calendar_sync.domain.logic.GooglePushPlanner
import com.singularity.todo.feature.calendar_sync.domain.logic.toShadow
import com.singularity.todo.feature.calendar_sync.domain.model.ChangePage
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleCalendarSummary
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEvent
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventId
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventStatus
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarEventSource
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderType
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeAppDatabase
import com.singularity.todo.test.fakes.FakeReminderRepository
import com.singularity.todo.test.fakes.FakeReminderScheduler
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.TestUsers
import com.singularity.todo.test.helpers.MutableClock
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The two defects found by auditing the feature rather than testing it, both of the same
 * shape: the local side was handled correctly and the *effect* was missing.
 *
 * A cancelled Google event used to be resurrected by the push walk, and a reminder moved by
 * a remote change kept its old alarm. Neither had a failing test, because neither had a test
 * that asked the right question.
 */
@Tag("fast")
class GoogleSyncLifecycleTest {

    private val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val clock = MutableClock(t0)
    private val utc = TimeZone.UTC
    private val userId = TestUsers.DEFAULT

    private val noon = LocalDateTime(2026, 3, 4, 12, 0).toInstant(utc)
    private val twoPm = LocalDateTime(2026, 3, 4, 14, 0).toInstant(utc)
    private val thursdayNoon = LocalDateTime(2026, 3, 5, 12, 0).toInstant(utc)

    private fun LocalDateTime(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        kotlinx.datetime.LocalDateTime(LocalDate(y, mo, d), LocalTime(h, mi))

    // ─── Bug 1: a cancelled event came back ─────────────────────────────

    private class RecordingSource(private val events: List<GoogleEvent>) : CalendarEventSource {
        val inserts = mutableListOf<GoogleEvent>()
        val patches = mutableListOf<GoogleEvent>()

        override suspend fun listCalendars(): List<GoogleCalendarSummary> = emptyList()

        override suspend fun fetchChanges(calendarId: String, syncToken: String?) =
            ChangePage(events = events, nextSyncToken = "t1")

        override suspend fun insert(calendarId: String, event: GoogleEvent): GoogleEvent {
            inserts += event
            return event.copy(id = GoogleEventId("generated-${inserts.size}"))
        }

        override suspend fun patch(
            calendarId: String,
            eventId: GoogleEventId,
            etag: String?,
            event: GoogleEvent,
        ): GoogleEvent {
            patches += event
            return event
        }

        override suspend fun get(calendarId: String, eventId: GoogleEventId) =
            GoogleEvent(id = eventId, calendarId = calendarId)

        override suspend fun cancel(calendarId: String, eventId: GoogleEventId, etag: String?) = Unit
    }

    private val db = FakeAppDatabase()
    private val taskRepo = FakeTaskRepository()
    private val reminders = FakeReminderRepository()
    private val scheduler = FakeReminderScheduler()

    private fun applier() = GoogleTaskApplier(
        taskRepository = taskRepo,
        reminderRepository = reminders,
        reminderScheduler = scheduler,
        shadowDao = db.googleEventShadowDao(),
        importDao = db.calendarImportEventDao(),
        userId = userId,
        clock = clock,
        idGenerator = UlidIdGenerator,
        timeZone = { utc },
    )

    private fun engine(source: CalendarEventSource) = GoogleSyncEngine(
        eventSource = source,
        shadowDao = db.googleEventShadowDao(),
        stateDao = db.calendarSyncStateDao(),
        importDao = db.calendarImportEventDao(),
        userId = userId.value,
        clock = clock,
        applier = applier(),
    )

    private fun task(title: String = "Dentist", dueDate: LocalDate? = LocalDate(2026, 3, 4)) = Task(
        id = TaskId("task-1"),
        title = title,
        dueDate = dueDate,
        dueTime = LocalTime(12, 0),
        createdAt = t0,
        updatedAt = t0,
        userId = userId,
    )

    private fun event(status: GoogleEventStatus) = GoogleEvent(
        id = GoogleEventId("evt-1"),
        calendarId = "primary",
        title = "Dentist",
        description = null,
        startsAt = noon,
        endsAt = twoPm,
        allDay = false,
        location = null,
        recurrenceRule = null,
        status = status,
        etag = "etag-1",
        updatedAt = null,
        taskId = null,
    )

    private suspend fun seedShadow(
        event: GoogleEvent = event(GoogleEventStatus.Confirmed),
        taskId: String = "task-1",
    ) {
        db.googleEventShadowDao().upsert(
            GoogleEventShadowEntity(
                userId = userId.value,
                eventId = event.id.value,
                taskId = taskId,
                calendarId = "primary",
                etag = event.etag,
                baseJson = EventShadowCodec.encode(event.toShadow()),
                remoteUpdatedAt = null,
                lastSyncedAt = t0.toEpochMilliseconds(),
            ),
        )
    }

    @Test
    fun `cancelling an event in Google does not bring it back`() = runTest {
        // The task survives the cancellation — the user cancelled an *event* — so the push
        // walk still sees it. With the shadow deleted it looked like a task that had never
        // been synced, and the planner re-created exactly what was just cancelled.
        taskRepo.seed(task())
        seedShadow()
        val source = RecordingSource(listOf(event(GoogleEventStatus.Cancelled)))

        val result = engine(source).sync("primary")

        assertTrue(source.inserts.isEmpty(), "a cancelled event must not be re-created: ${source.inserts}")
        assertEquals(0, result.pushed)
    }

    @Test
    fun `cancelling leaves a tombstone rather than deleting the mapping`() = runTest {
        taskRepo.seed(task())
        seedShadow()
        val source = RecordingSource(listOf(event(GoogleEventStatus.Cancelled)))

        engine(source).sync("primary")

        val shadow = db.googleEventShadowDao().get(userId.value, "evt-1")
        assertNotNull(shadow, "the shadow must survive, or the next pass resurrects the event")
        assertNotNull(shadow?.cancelledAt, "it must be marked cancelled, not merely present")
    }

    @Test
    fun `a tombstone survives the sweep that runs in the same pass`() = runTest {
        // The pass deletes shadows whose event is no longer in the listing, and a cancelled
        // event is not in the listing. Without feeding the tombstone id into the keep-list it
        // would be written and removed within one pass.
        taskRepo.seed(task())
        seedShadow()
        val source = RecordingSource(listOf(event(GoogleEventStatus.Cancelled)))

        engine(source).sync("primary")

        assertNotNull(db.googleEventShadowDao().get(userId.value, "evt-1")?.cancelledAt)
    }

    @Test
    fun `the task behind a cancelled event survives`() = runTest {
        // Deleting a user's task because they cancelled an appointment would be a far more
        // surprising outcome than the one they asked for.
        taskRepo.seed(task())
        seedShadow()
        val source = RecordingSource(listOf(event(GoogleEventStatus.Cancelled)))

        engine(source).sync("primary")

        assertEquals("Dentist", taskRepo.get(TaskId("task-1"))?.title)
    }

    @Test
    fun `the planner does not insert a task whose event was cancelled`() = runTest {
        val plan = GooglePushPlanner.plan(
            desired = listOf(event(GoogleEventStatus.Confirmed).copy(taskId = "task-1")),
            shadows = listOf(
                EventShadowRef(
                    eventId = "evt-1",
                    calendarId = "primary",
                    taskId = "task-1",
                    etag = "etag-1",
                    base = EventShadow(title = "Dentist", startsAt = noon, endsAt = twoPm),
                    cancelledAt = t0.toEpochMilliseconds(),
                ),
            ),
        )

        assertTrue(plan.isEmpty(), "a cancelled event must not be planned for creation again")
    }

    @Test
    fun `a live event still gets patched`() = runTest {
        // The tombstone must not become a blanket "never write" that also stops real updates.
        taskRepo.seed(task(title = "New title"))
        seedShadow()
        val source = RecordingSource(listOf(event(GoogleEventStatus.Confirmed)))

        val result = engine(source).sync("primary")

        assertEquals(1, result.pushed)
        assertNull(db.googleEventShadowDao().get(userId.value, "evt-1")?.cancelledAt)
    }

    // ─── Bug 2: the alarm kept the old time ─────────────────────────────

    private fun reminder(fireAt: Long, recurringPattern: String? = null) = Reminder(
        id = ReminderId("rem-1"),
        taskId = TaskId("task-1"),
        userId = userId,
        type = ReminderType.Gentle,
        offsetMinutes = -10,
        fireAt = fireAt,
        recurringPattern = recurringPattern,
    )

    @Test
    fun `moving a task re-arms the reminder alarm`() = runTest {
        // The row moves; nothing observes it, so without an explicit re-arm the notification
        // fires at the *old* time and the new time is never armed at all.
        taskRepo.seed(task())
        reminders.seed(reminder(fireAt = noon.toEpochMilliseconds() - 600_000))
        scheduler.schedule(reminder(fireAt = noon.toEpochMilliseconds() - 600_000))
        seedShadow()
        val moved = event(GoogleEventStatus.Confirmed).copy(startsAt = thursdayNoon, endsAt = thursdayNoon)
        val source = RecordingSource(listOf(moved))

        engine(source).sync("primary")

        val armed = assertNotNull(scheduler.armedFor(ReminderId("rem-1"), userId))
        assertEquals(thursdayNoon.toEpochMilliseconds() - 600_000, armed.fireAt)
    }

    @Test
    fun `the old alarm is cancelled before the new one is armed`() = runTest {
        // Android keys alarms by reminder id, so re-scheduling without cancelling leaves the
        // old one in place and the user gets two notifications.
        taskRepo.seed(task())
        val original = reminder(fireAt = noon.toEpochMilliseconds() - 600_000)
        reminders.seed(original)
        scheduler.schedule(original)
        seedShadow()
        val moved = event(GoogleEventStatus.Confirmed).copy(startsAt = thursdayNoon, endsAt = thursdayNoon)

        engine(RecordingSource(listOf(moved))).sync("primary")

        assertTrue(ReminderId("rem-1") in scheduler.cancelled, "the old alarm must be dropped")
    }

    @Test
    fun `a rename does not re-arm anything`() = runTest {
        // A reminder shifted by the length of a new title is the bug that made this look like
        // "notifications fire at random times".
        taskRepo.seed(task())
        val original = reminder(fireAt = noon.toEpochMilliseconds() - 600_000)
        reminders.seed(original)
        scheduler.schedule(original)
        seedShadow()
        val armedBefore = scheduler.scheduled.size
        val renamed = event(GoogleEventStatus.Confirmed).copy(title = "Dentist (moved)")

        engine(RecordingSource(listOf(renamed))).sync("primary")

        assertEquals(armedBefore, scheduler.scheduled.size, "a rename must not touch the alarms")
        assertEquals(original.fireAt, assertNotNull(reminders.get(ReminderId("rem-1"))).fireAt)
    }

    @Test
    fun `a recurring reminder is not re-armed`() = runTest {
        // Its `fireAt` is the next occurrence of a pattern, not a point in time.
        taskRepo.seed(task())
        val original = reminder(fireAt = noon.toEpochMilliseconds() - 600_000, recurringPattern = "FREQ=DAILY")
        reminders.seed(original)
        scheduler.schedule(original)
        seedShadow()
        val armedBefore = scheduler.scheduled.size
        val moved = event(GoogleEventStatus.Confirmed).copy(startsAt = thursdayNoon, endsAt = thursdayNoon)

        engine(RecordingSource(listOf(moved))).sync("primary")

        assertEquals(armedBefore, scheduler.scheduled.size, "a recurring series must not be re-armed from one date")
    }

    @Test
    fun `a failed alarm does not abandon the rest of the pass`() = runTest {
        // A late notification is recoverable; abandoning the import and the task edits that
        // were already applied is not.
        val failing = FakeReminderScheduler(failOnSchedule = true)
        val applier = GoogleTaskApplier(
            taskRepository = taskRepo,
            reminderRepository = reminders,
            reminderScheduler = failing,
            shadowDao = db.googleEventShadowDao(),
            importDao = db.calendarImportEventDao(),
            userId = userId,
            clock = clock,
            idGenerator = UlidIdGenerator,
            timeZone = { utc },
        )
        taskRepo.seed(task(title = "New title"))
        val original = reminder(fireAt = noon.toEpochMilliseconds() - 600_000)
        reminders.seed(original)
        seedShadow()
        val moved = event(GoogleEventStatus.Confirmed).copy(startsAt = thursdayNoon, endsAt = thursdayNoon)
        val source = RecordingSource(listOf(moved))

        val result = GoogleSyncEngine(
            eventSource = source,
            shadowDao = db.googleEventShadowDao(),
            stateDao = db.calendarSyncStateDao(),
            importDao = db.calendarImportEventDao(),
            userId = userId.value,
            clock = clock,
            applier = applier,
        ).sync("primary")

        // The task edit still landed, and the pass did not fail.
        assertEquals("New title", taskRepo.get(TaskId("task-1"))?.title)
        assertEquals(0, result.failed)
    }
}