package com.singularity.todo.feature.calendar_sync.sync

import com.singularity.todo.core.ids.UlidIdGenerator
import com.singularity.todo.feature.calendar_sync.data.CalendarImportEventEntity
import com.singularity.todo.feature.calendar_sync.domain.logic.EventShadowCodec
import com.singularity.todo.feature.calendar_sync.domain.logic.FieldOutcome
import com.singularity.todo.feature.calendar_sync.domain.logic.MergeResult
import com.singularity.todo.feature.calendar_sync.domain.logic.toShadow
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEvent
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventId
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventStatus
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.reminders.ReminderId
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
 * Applying Google's decisions to the app's own tasks and reminders.
 *
 * This is the seam where a correct merge can still deliver nothing. Every case here is one
 * where the *wrong* behaviour is silent: an import that creates no task, a rename that
 * also moves a reminder, a reschedule that leaves the notification at the old time.
 */
@Tag("fast")
class GoogleTaskApplierTest {

    private val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val clock = MutableClock(t0)
    private val utc = TimeZone.UTC

    // The profile-aware fakes scope every read and write to their own notion of "the
    // current user", so the applier has to be given the same id the fakes default to —
    // otherwise every seeded task reads back as absent and the tests pass vacuously.
    private val userId = TestUsers.DEFAULT

    private val db = FakeAppDatabase()
    private val taskRepo = FakeTaskRepository()
    private val reminders = FakeReminderRepository()
    private val scheduler = FakeReminderScheduler()

    /** Every task the fake holds, regardless of scoping — for asserting what was created. */
    private fun allTasks(): List<Task> = taskRepo.tasks.value.values.toList()

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

    private val noon = LocalDateTime(2026, 3, 4, 12, 0).toInstant(utc)
    private val onePm = LocalDateTime(2026, 3, 4, 13, 0).toInstant(utc)

    private fun event(
        title: String? = "Dentist",
        startsAt: Instant? = noon,
        endsAt: Instant? = onePm,
        allDay: Boolean = false,
        status: GoogleEventStatus = GoogleEventStatus.Confirmed,
    ) = GoogleEvent(
        id = GoogleEventId("evt-1"),
        calendarId = "primary",
        title = title,
        description = null,
        startsAt = startsAt,
        endsAt = endsAt,
        allDay = allDay,
        location = null,
        recurrenceRule = null,
        status = status,
        etag = "etag-1",
        updatedAt = null,
        taskId = null,
    )

    private fun importRow(
        eventId: String = "evt-1",
        title: String? = "Dentist",
        startsAt: Long? = noon.toEpochMilliseconds(),
        endsAt: Long? = onePm.toEpochMilliseconds(),
        allDay: Boolean = false,
        taskId: String? = null,
    ) = CalendarImportEventEntity(
        userId = userId.value,
        eventId = eventId,
        calendarId = "primary",
        title = title,
        description = null,
        location = null,
        startsAt = startsAt,
        endsAt = endsAt,
        allDay = allDay,
        recurrenceRule = null,
        etag = "etag-1",
        lastSyncedAt = t0.toEpochMilliseconds(),
        importedAt = t0.toEpochMilliseconds(),
        taskId = taskId,
    )

    private fun task(
        id: String = "task-1",
        title: String = "Dentist",
        dueDate: LocalDate? = LocalDate(2026, 3, 4),
        dueTime: LocalTime? = LocalTime(12, 0),
    ) = Task(
        id = TaskId(id),
        title = title,
        dueDate = dueDate,
        dueTime = dueTime,
        createdAt = t0,
        updatedAt = t0,
        userId = userId,
    )

    // ─── Import ─────────────────────────────────────────────────────────

    @Test
    fun `a staged foreign event becomes a real task`() = runTest {
        db.calendarImportEventDao().upsert(importRow())

        val result = applier().adoptPendingImports()

        assertEquals(1, result.imported)
        assertEquals(0, result.failed)
        val created = allTasks().single()
        assertEquals("Dentist", created.title)
        assertEquals(LocalDate(2026, 3, 4), created.dueDate)
        assertEquals(LocalTime(12, 0), created.dueTime)
    }

    @Test
    fun `importing links the import row to the new task`() = runTest {
        db.calendarImportEventDao().upsert(importRow())

        applier().adoptPendingImports()

        val row = assertNotNull(db.calendarImportEventDao().get(userId.value, "evt-1"))
        assertNotNull(row.taskId)
    }

    @Test
    fun `importing writes a shadow so the event is two-way from then on`() = runTest {
        // Without the shadow the next pass sees no recorded ancestor, every field reads as a
        // conflict, and the event is imported once and never synced again.
        db.calendarImportEventDao().upsert(importRow())

        applier().adoptPendingImports()

        val shadow = assertNotNull(db.googleEventShadowDao().get(userId.value, "evt-1"))
        assertEquals(assertNotNull(shadow.taskId), allTasks().single().id.value)
        assertEquals(EventShadowCodec.encode(event().toShadow()), shadow.baseJson)
    }

    @Test
    fun `an already-converted event is not imported twice`() = runTest {
        db.calendarImportEventDao().upsert(importRow(taskId = "task-existing"))

        val result = applier().adoptPendingImports()

        assertEquals(0, result.imported)
        assertTrue(allTasks().isEmpty())
    }

    @Test
    fun `a second pass is a no-op once everything is converted`() = runTest {
        db.calendarImportEventDao().upsert(importRow())
        val applier = applier()
        applier.adoptPendingImports()

        val second = applier.adoptPendingImports()

        assertEquals(0, second.imported)
        assertEquals(1, allTasks().size)
    }

    @Test
    fun `an all-day event imports with a date and no time`() = runTest {
        db.calendarImportEventDao().upsert(importRow(allDay = true))

        applier().adoptPendingImports()

        val created = allTasks().single()
        assertNotNull(created.dueDate)
        assertNull(created.dueTime)
    }

    @Test
    fun `an event with no start still imports as an unscheduled task`() = runTest {
        // The user can still see it, rename it, and give it a date. Dropping it would lose
        // a real appointment that Google simply did not put a time on.
        db.calendarImportEventDao().upsert(importRow(startsAt = null, endsAt = null))

        val result = applier().adoptPendingImports()

        assertEquals(1, result.imported)
        assertNull(allTasks().single().dueDate)
    }

    @Test
    fun `a rejected import does not stop the others`() = runTest {
        taskRepo.createOverride = Result.failure(IllegalStateException("validator said no"))
        db.calendarImportEventDao().upsert(importRow(eventId = "evt-1"))
        db.calendarImportEventDao().upsert(importRow(eventId = "evt-2"))

        val result = applier().adoptPendingImports()

        assertEquals(2, result.failed)
        // Nothing linked, so both remain eligible on a later pass.
        assertNull(db.calendarImportEventDao().get(userId.value, "evt-1")?.taskId)
    }

    // ─── Remote change application ───────────────────────────────────────

    @Test
    fun `a remotely renamed task is renamed in the app`() = runTest {
        taskRepo.seed(task())
        val merge = mergeOf(title = FieldOutcome.ApplyToTask("Dentist (moved)"))

        val result = applier().applyRemoteChange(TaskId("task-1"), merge, event())

        assertEquals(1, result.updated)
        assertEquals("Dentist (moved)", taskRepo.get(TaskId("task-1"))?.title)
    }

    @Test
    fun `a remotely cleared description clears it locally`() = runTest {
        taskRepo.seed(task().copy(description = "old notes"))
        val merge = mergeOf(description = FieldOutcome.ApplyToTask(null))

        applier().applyRemoteChange(TaskId("task-1"), merge, event())

        assertNull(taskRepo.get(TaskId("task-1"))?.description)
    }

    @Test
    fun `a remotely cleared title still leaves the task named`() = runTest {
        taskRepo.seed(task())
        val merge = mergeOf(title = FieldOutcome.ApplyToTask(null))

        applier().applyRemoteChange(TaskId("task-1"), merge, event())

        assertEquals(
            com.singularity.todo.feature.calendar_sync.domain.logic.GoogleEventTaskMapper.UNTITLED,
            taskRepo.get(TaskId("task-1"))?.title,
        )
    }

    @Test
    fun `a merge with nothing to apply leaves the task alone`() = runTest {
        taskRepo.seed(task())
        val before = taskRepo.get(TaskId("task-1"))

        val result = applier().applyRemoteChange(TaskId("task-1"), mergeOf(), event())

        assertEquals(0, result.updated)
        assertEquals(before, taskRepo.get(TaskId("task-1")))
    }

    @Test
    fun `a push outcome is never applied to the task`() = runTest {
        // A Push is the app's own value on its way to Google. Applying it would overwrite
        // the user's own edit with the value the merge deliberately kept.
        taskRepo.seed(task(title = "Local title"))
        val merge = mergeOf(title = FieldOutcome.Push("Local title"))

        val result = applier().applyRemoteChange(TaskId("task-1"), merge, event(title = "Remote title"))

        assertEquals(0, result.updated)
        assertEquals("Local title", taskRepo.get(TaskId("task-1"))?.title)
    }

    @Test
    fun `a conflict is never applied to the task`() = runTest {
        taskRepo.seed(task(title = "Local title"))
        val merge = mergeOf(title = FieldOutcome.Conflict("Local title", "Remote title"))

        val result = applier().applyRemoteChange(TaskId("task-1"), merge, event(title = "Remote title"))

        assertEquals(0, result.updated)
        assertEquals("Local title", taskRepo.get(TaskId("task-1"))?.title)
    }

    @Test
    fun `a cancelled event never rewrites the task`() = runTest {
        taskRepo.seed(task())
        val merge = mergeOf(title = FieldOutcome.ApplyToTask("Gone"))

        val result = applier().applyRemoteChange(
            TaskId("task-1"),
            merge,
            event(status = GoogleEventStatus.Cancelled),
        )

        assertEquals(0, result.updated)
        assertEquals("Dentist", taskRepo.get(TaskId("task-1"))?.title)
    }

    @Test
    fun `a change for a task that no longer exists is not an error`() = runTest {
        val merge = mergeOf(title = FieldOutcome.ApplyToTask("Whatever"))

        val result = applier().applyRemoteChange(TaskId("deleted"), merge, event())

        assertEquals(0, result.updated)
        assertEquals(0, result.failed)
    }

    // ─── Reminders ──────────────────────────────────────────────────────

    @Test
    fun `moving a task moves its reminder by the same delta`() = runTest {
        val task = task(dueDate = LocalDate(2026, 3, 4), dueTime = LocalTime(12, 0))
        taskRepo.seed(task)
        reminders.seed(reminderFor("task-1", fireAt = noon.toEpochMilliseconds() - 600_000))
        // Google moved the event two days later.
        val merge = mergeOf(startsAt = FieldOutcome.ApplyToTask(LocalDateTime(2026, 3, 6, 12, 0).toInstant(utc)))

        val result = applier().applyRemoteChange(
            TaskId("task-1"),
            merge,
            event(startsAt = LocalDateTime(2026, 3, 6, 12, 0).toInstant(utc)),
        )

        assertEquals(1, result.remindersMoved)
        val moved = assertNotNull(reminders.get(ReminderId("rem-1")))
        // Two days later, and still ten minutes before — the user's chosen offset, not a
        // recomputed one.
        assertEquals(noon.toEpochMilliseconds() + 2 * 86_400_000 - 600_000, moved.fireAt)
    }

    @Test
    fun `a rename does not move the reminder`() = runTest {
        // A reminder shifted by the length of a new title is the kind of bug that gets
        // reported as "notifications fire at random times".
        taskRepo.seed(task())
        val original = noon.toEpochMilliseconds() - 600_000
        reminders.seed(reminderFor("task-1", fireAt = original))

        val result = applier().applyRemoteChange(
            TaskId("task-1"),
            mergeOf(title = FieldOutcome.ApplyToTask("A much much longer title")),
            event(),
        )

        assertEquals(0, result.remindersMoved)
        assertEquals(original, assertNotNull(reminders.get(ReminderId("rem-1"))).fireAt)
    }

    @Test
    fun `a recurring reminder is left where it is`() = runTest {
        // Its `fireAt` is the next occurrence of a pattern, not a single point in time;
        // rewriting it from a new due date would corrupt the series.
        taskRepo.seed(task())
        val original = noon.toEpochMilliseconds() - 600_000
        reminders.seed(reminderFor("task-1", fireAt = original, recurringPattern = "FREQ=DAILY"))

        val result = applier().applyRemoteChange(
            TaskId("task-1"),
            mergeOf(startsAt = FieldOutcome.ApplyToTask(LocalDateTime(2026, 3, 6, 12, 0).toInstant(utc))),
            event(startsAt = LocalDateTime(2026, 3, 6, 12, 0).toInstant(utc)),
        )

        assertEquals(0, result.remindersMoved)
        assertEquals(original, assertNotNull(reminders.get(ReminderId("rem-1"))).fireAt)
    }

    @Test
    fun `a task with no reminders moves no reminders`() = runTest {
        taskRepo.seed(task())

        val result = applier().applyRemoteChange(
            TaskId("task-1"),
            mergeOf(startsAt = FieldOutcome.ApplyToTask(LocalDateTime(2026, 3, 6, 12, 0).toInstant(utc))),
            event(startsAt = LocalDateTime(2026, 3, 6, 12, 0).toInstant(utc)),
        )

        assertEquals(1, result.updated)
        assertEquals(0, result.remindersMoved)
    }

    @Test
    fun `a failed task write does not move the reminders`() = runTest {
        // Otherwise the notification moves for a task that did not, and the two disagree
        // permanently.
        taskRepo.seed(task())
        taskRepo.updateOverride = Result.failure(IllegalStateException("write rejected"))
        reminders.seed(reminderFor("task-1", fireAt = noon.toEpochMilliseconds() - 600_000))

        val result = applier().applyRemoteChange(
            TaskId("task-1"),
            mergeOf(startsAt = FieldOutcome.ApplyToTask(LocalDateTime(2026, 3, 6, 12, 0).toInstant(utc))),
            event(startsAt = LocalDateTime(2026, 3, 6, 12, 0).toInstant(utc)),
        )

        assertEquals(1, result.failed)
        assertEquals(0, result.remindersMoved)
        assertEquals(noon.toEpochMilliseconds() - 600_000, assertNotNull(reminders.get(ReminderId("rem-1"))).fireAt)
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    /**
     * A merge with [title]/[description]/[startsAt] decided and the rest untouched.
     *
     * The field order matches the one `EventShadow` declares, which is what
     * `BidirectionalMerge` emits; a merge assembled in any other order would silently
     * mislabel every outcome.
     */
    private fun mergeOf(
        title: FieldOutcome = FieldOutcome.NoOp,
        description: FieldOutcome = FieldOutcome.NoOp,
        startsAt: FieldOutcome = FieldOutcome.NoOp,
    ) = MergeResult(
        eventId = GoogleEventId("evt-1"),
        fields = listOf(
            title,
            description,
            startsAt,
            FieldOutcome.NoOp,
            FieldOutcome.NoOp,
            FieldOutcome.NoOp,
            FieldOutcome.NoOp,
        ),
        hasConflict = false,
    )

    private fun reminderFor(taskId: String, fireAt: Long, recurringPattern: String? = null) = Reminder(
        id = ReminderId("rem-1"),
        taskId = TaskId(taskId),
        userId = userId,
        type = com.singularity.todo.feature.reminders.ReminderType.Gentle,
        offsetMinutes = -10,
        fireAt = fireAt,
        recurringPattern = recurringPattern,
    )
}
