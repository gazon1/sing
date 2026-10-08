package com.singularity.todo.feature.calendar_sync.sync

import com.singularity.todo.core.ids.UuidIdGenerator
import com.singularity.todo.feature.calendar_sync.data.GoogleEventShadowEntity
import com.singularity.todo.feature.calendar_sync.domain.logic.EventShadow
import com.singularity.todo.feature.calendar_sync.domain.logic.EventShadowCodec
import com.singularity.todo.feature.calendar_sync.domain.model.ChangePage
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleCalendarSummary
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEvent
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventId
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventStatus
import com.singularity.todo.feature.calendar_sync.domain.model.ImportWindow
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarEventSource
import com.singularity.todo.test.fakes.FakeAppDatabase
import com.singularity.todo.test.fakes.FakeReminderRepository
import com.singularity.todo.test.fakes.FakeReminderScheduler
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.TestUsers
import com.singularity.todo.test.helpers.MutableClock
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The engine's **push** direction, against a real applier and a real task table.
 *
 * This is the half that was missing and nothing caught. The two tests in
 * [GoogleSyncEngineTest] that touch the patch list assert it stays *empty* — which is
 * exactly what a pass with no local side at all would also produce. So the engine could
 * compute a merge, never produce a `Push`, never call `patch`, and pass every test it had.
 *
 * The tell was the construction of the merge's `ours` argument from the shadow: an "ours" that
 * is always equal to the ancestor answers "did anything change locally?" with a permanent no.
 */
@Tag("fast")
class GoogleSyncEnginePushTest {

    private val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val clock = MutableClock(t0)
    private val utc = TimeZone.UTC
    private val userId = TestUsers.DEFAULT

    private val noon = LocalDateTime(2026, 3, 4, 12, 0).toInstant(utc)

    private fun LocalDateTime(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        kotlinx.datetime.LocalDateTime(LocalDate(y, mo, d), LocalTime(h, mi))

    /** Records what the engine asked Google to do. */
    private class RecordingSource(private val events: List<GoogleEvent>) : CalendarEventSource {
        val patches = mutableListOf<GoogleEvent>()
        val inserts = mutableListOf<GoogleEvent>()

        override suspend fun listCalendars(): List<GoogleCalendarSummary> = emptyList()

        override suspend fun fetchChanges(calendarId: String, syncToken: String?) =
            ChangePage(events = events, nextSyncToken = "token-1")

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
    private val source = RecordingSource(emptyList())

    private fun applier() = GoogleTaskApplier(
        taskRepository = taskRepo,
        reminderRepository = reminders,
        reminderScheduler = scheduler,
        shadowDao = db.googleEventShadowDao(),
        importDao = db.calendarImportEventDao(),
        userId = userId,
        clock = clock,
        idGenerator = UuidIdGenerator,
        timeZone = { utc },
    )

    private fun remoteEvent(
        title: String? = "Old title",
        endsAt: Instant? = noon,
        location: String? = "Clinic",
        recurrenceRule: String? = null,
    ) = GoogleEvent(
        id = GoogleEventId("evt-1"),
        calendarId = "primary",
        title = title,
        description = null,
        startsAt = noon,
        endsAt = endsAt,
        allDay = false,
        location = location,
        recurrenceRule = recurrenceRule,
        status = GoogleEventStatus.Confirmed,
        etag = "etag-1",
        updatedAt = null,
        taskId = null,
    )

    // ─── The regression this file exists for ────────────────────────────

    @Test
    fun `a renamed task reaches Google`() = runTest {
        // The remote event has a recorded ancestor and an unchanged task id; the local task
        // has moved on. Before the fix `ours` was built from the shadow, so `localChanged`
        // was always false and `patch` was unreachable.
        taskRepo.seed(
            com.singularity.todo.feature.tasks.domain.model.Task(
                id = com.singularity.todo.feature.tasks.domain.model.TaskId("task-1"),
                title = "New title",
                dueDate = LocalDate(2026, 3, 4),
                dueTime = LocalTime(12, 0),
                createdAt = t0,
                updatedAt = t0,
                userId = userId,
            ),
        )
        writeShadow(base = EventShadow(title = "Old title", startsAt = noon, endsAt = noon))

        val result = engineWith(remoteEvent(title = "Old title")).sync("primary")

        assertEquals(1, result.pushed, "a renamed task must be pushed to Google")
        assertEquals("New title", source.patches.single().title)
    }

    @Test
    fun `a pushed patch preserves the location Google holds`() = runTest {
        // The task has no location field at all, so a task-derived payload always has
        // `location = null`. Sending that would erase the address on a real entry.
        taskRepo.seed(
            com.singularity.todo.feature.tasks.domain.model.Task(
                id = com.singularity.todo.feature.tasks.domain.model.TaskId("task-1"),
                title = "New title",
                createdAt = t0,
                updatedAt = t0,
                userId = userId,
            ),
        )
        writeShadow(base = EventShadow(title = "Old title", location = "Clinic"))
        val event = remoteEvent(title = "Old title", location = "Clinic")
        val engine = engineWith(event)

        engine.sync("primary")

        assertEquals("Clinic", source.patches.single().location)
    }

    @Test
    fun `an unchanged task produces no write at all`() = runTest {
        // Otherwise every pass bumps the event and then syncs that bump back out.
        taskRepo.seed(
            com.singularity.todo.feature.tasks.domain.model.Task(
                id = com.singularity.todo.feature.tasks.domain.model.TaskId("task-1"),
                title = "Same",
                dueDate = LocalDate(2026, 3, 4),
                dueTime = LocalTime(12, 0),
                createdAt = t0,
                updatedAt = t0,
                userId = userId,
            ),
        )
        // `endsAt` is null on both sides because the task has no end date. The first version
        // of this test put `endsAt = noon` in the shadow while the task implied null — so the
        // push it complained about was correct, and the test was asserting that a real
        // disagreement was not a disagreement.
        writeShadow(base = EventShadow(title = "Same", startsAt = noon, endsAt = null))

        val result = engineWith(remoteEvent(title = "Same", endsAt = null)).sync("primary")

        assertTrue(source.patches.isEmpty(), "an unchanged task must not be written")
        assertEquals(0, result.pushed)
    }

    // ─── Insert: the case the pull walk structurally cannot see ──────────

    @Test
    fun `a task with no Google event yet is inserted`() = runTest {
        taskRepo.seed(
            com.singularity.todo.feature.tasks.domain.model.Task(
                id = com.singularity.todo.feature.tasks.domain.model.TaskId("task-1"),
                title = "Brand new",
                createdAt = t0,
                updatedAt = t0,
                userId = userId,
            ),
        )

        val result = engineWith().sync("primary")

        assertEquals(1, result.pushed)
        assertEquals("Brand new", source.inserts.single().title)
    }

    @Test
    fun `a completed task is not put on the calendar`() = runTest {
        taskRepo.seed(
            com.singularity.todo.feature.tasks.domain.model.Task(
                id = com.singularity.todo.feature.tasks.domain.model.TaskId("task-1"),
                title = "Done already",
                completedAt = t0,
                createdAt = t0,
                updatedAt = t0,
                userId = userId,
            ),
        )

        engineWith().sync("primary")

        assertTrue(source.inserts.isEmpty(), "a completed task must not be created in Google")
    }

    @Test
    fun `a trashed task is not put on the calendar`() = runTest {
        taskRepo.seed(
            com.singularity.todo.feature.tasks.domain.model.Task(
                id = com.singularity.todo.feature.tasks.domain.model.TaskId("task-1"),
                title = "Bin",
                archivedAt = t0,
                createdAt = t0,
                updatedAt = t0,
                userId = userId,
            ),
        )

        engineWith().sync("primary")

        assertTrue(source.inserts.isEmpty(), "a trashed task must not be created in Google")
    }

    @Test
    fun `an inserted event gets a shadow so the next pass can update it`() = runTest {
        // Without one, the next pass sees an event with no recorded ancestor, every field
        // reads as a conflict, and the task is inserted once and never synced again.
        taskRepo.seed(
            com.singularity.todo.feature.tasks.domain.model.Task(
                id = com.singularity.todo.feature.tasks.domain.model.TaskId("task-1"),
                title = "Brand new",
                createdAt = t0,
                updatedAt = t0,
                userId = userId,
            ),
        )

        engineWith().sync("primary")

        val shadow = db.googleEventShadowDao().get(userId.value, "generated-1")
        assertEquals("task-1", shadow?.taskId)
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private suspend fun writeShadow(base: EventShadow, taskId: String = "task-1") {
        db.googleEventShadowDao().upsert(
            GoogleEventShadowEntity(
                userId = userId.value,
                eventId = "evt-1",
                taskId = taskId,
                calendarId = "primary",
                etag = "etag-1",
                baseJson = EventShadowCodec.encode(base),
                remoteUpdatedAt = null,
                lastSyncedAt = t0.toEpochMilliseconds(),
            ),
        )
    }

    private fun engineWith(vararg events: GoogleEvent) = GoogleSyncEngine(
        eventSource = object : CalendarEventSource by source {
            override suspend fun fetchChanges(calendarId: String, syncToken: String?) =
                ChangePage(events = events.toList(), nextSyncToken = "t1")
        },
        importWindow = ImportWindow.DEFAULT,
        shadowDao = db.googleEventShadowDao(),
        stateDao = db.calendarSyncStateDao(),
        importDao = db.calendarImportEventDao(),
        userId = userId.value,
        clock = clock,
        applier = applier(),
    )
}
