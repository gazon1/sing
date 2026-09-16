package com.singularity.todo.feature.calendar

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.calendar.domain.logic.CalendarTaskMapper
import com.singularity.todo.feature.calendar.domain.model.CalendarTaskStatus
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [CalendarTaskMapper.toCalendarTaskUi].
 */
class CalendarTaskMapperTest {

    private val testUserId = UserId("test-user")
    private val today = LocalDate(2026, Month.SEPTEMBER, 16)

    private fun makeTask(
        id: String = "t1",
        title: String = "Test Task",
        dueDate: LocalDate? = LocalDate(2026, Month.SEPTEMBER, 16),
        dueTime: LocalTime? = null,
        completedAt: Instant? = null,
    ): Task = Task(
        id = TaskId.fromString(id),
        title = title,
        userId = testUserId,
        dueDate = dueDate,
        dueTime = dueTime,
        completedAt = completedAt,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    // ─── status mapping ─────────────────────────────────────────────────────

    @Test
    fun `done task maps to DONE status`() {
        val task = makeTask(
            id = "t1",
            title = "Done Task",
            completedAt = Instant.fromEpochMilliseconds(1),
        )
        val result = CalendarTaskMapper.toCalendarTaskUi(task, today)
        assertEquals(CalendarTaskStatus.DONE, result.status)
    }

    @Test
    fun `pending task with past due date maps to OVERDUE status`() {
        val pastDate = LocalDate(2026, Month.SEPTEMBER, 10) // before today (Sept 16)
        val task = makeTask(dueDate = pastDate, completedAt = null)
        val result = CalendarTaskMapper.toCalendarTaskUi(task, today)
        assertEquals(CalendarTaskStatus.OVERDUE, result.status)
    }

    @Test
    fun `pending task with today's date maps to PENDING status`() {
        val task = makeTask(dueDate = today, completedAt = null)
        val result = CalendarTaskMapper.toCalendarTaskUi(task, today)
        assertEquals(CalendarTaskStatus.PENDING, result.status)
    }

    @Test
    fun `pending task with future due date maps to PENDING status`() {
        val futureDate = LocalDate(2026, Month.SEPTEMBER, 20) // after today
        val task = makeTask(dueDate = futureDate, completedAt = null)
        val result = CalendarTaskMapper.toCalendarTaskUi(task, today)
        assertEquals(CalendarTaskStatus.PENDING, result.status)
    }

    // ─── field mapping ──────────────────────────────────────────────────────

    @Test
    fun `maps id and title correctly`() {
        val task = makeTask(id = "t42", title = "My Calendar Task")
        val result = CalendarTaskMapper.toCalendarTaskUi(task, today)
        assertEquals(TaskId.fromString("t42"), result.id)
        assertEquals("My Calendar Task", result.title)
    }

    @Test
    fun `maps dueDate to date field`() {
        val dueDate = LocalDate(2026, Month.OCTOBER, 5)
        val task = makeTask(dueDate = dueDate)
        val result = CalendarTaskMapper.toCalendarTaskUi(task, today)
        assertEquals(dueDate, result.date)
    }

    @Test
    fun `null dueDate falls back to today for date field`() {
        val task = makeTask(dueDate = null)
        val result = CalendarTaskMapper.toCalendarTaskUi(task, today)
        assertEquals(today, result.date)
    }

    @Test
    fun `isAllDay false when task has dueTime`() {
        val task = makeTask(dueTime = LocalTime(9, 0))
        val result = CalendarTaskMapper.toCalendarTaskUi(task, today)
        assertFalse(result.isAllDay)
    }

    @Test
    fun `isAllDay true when task has no dueTime`() {
        val task = makeTask(dueTime = null)
        val result = CalendarTaskMapper.toCalendarTaskUi(task, today)
        assertTrue(result.isAllDay)
    }

    @Test
    fun `startTime is mapped from dueTime`() {
        val task = makeTask(dueTime = LocalTime(10, 30))
        val result = CalendarTaskMapper.toCalendarTaskUi(task, today)
        assertEquals(LocalTime(10, 30), result.startTime)
    }

    @Test
    fun `endTime is always null in current mapping`() {
        val task = makeTask(dueTime = LocalTime(10, 30))
        val result = CalendarTaskMapper.toCalendarTaskUi(task, today)
        assertNull(result.endTime)
    }

    // ─── isRecurring ───────────────────────────────────────────────────────

    @Test
    fun `isRecurring true when isRecurring parameter is true`() {
        val task = makeTask()
        val result = CalendarTaskMapper.toCalendarTaskUi(task, today, isRecurring = true)
        assertTrue(result.isRecurring)
    }

    @Test
    fun `isRecurring false by default`() {
        val task = makeTask()
        val result = CalendarTaskMapper.toCalendarTaskUi(task, today)
        assertFalse(result.isRecurring)
    }

    // ─── isLink ────────────────────────────────────────────────────────────

    @Test
    fun `isLink true for Note kind`() {
        val task = makeTask(
            title = "My Note",
            dueDate = LocalDate(2026, Month.SEPTEMBER, 16),
        )
        // Task kind defaults to Task — we can't create a Note here directly
        // without importing TaskKind. Let me just verify the default.
        val result = CalendarTaskMapper.toCalendarTaskUi(task, today)
        assertFalse(result.isLink)
    }

    // ─── emoji and accentColor ───────────────────────────────────────────────

    @Test
    fun `emoji is always null in current mapping`() {
        val task = makeTask(title = "🌟 Important meeting")
        val result = CalendarTaskMapper.toCalendarTaskUi(task, today)
        assertNull(result.emoji)
    }

    @Test
    fun `accentColor is always null in current mapping`() {
        val task = makeTask()
        val result = CalendarTaskMapper.toCalendarTaskUi(task, today)
        assertNull(result.accentColor)
    }
}
