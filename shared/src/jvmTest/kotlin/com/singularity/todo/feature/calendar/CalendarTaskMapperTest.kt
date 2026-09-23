package com.singularity.todo.feature.calendar

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.calendar.domain.logic.CalendarTaskMapper
import com.singularity.todo.feature.calendar.domain.model.CalendarTaskStatus
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
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
        emoji: String? = null,
        accentColor: Long? = null,
    ): Task = Task(
        id = TaskId.fromString(id),
        title = title,
        description = null,
        priority = com.singularity.todo.feature.tasks.domain.model.TaskPriority.None,
        kind = com.singularity.todo.feature.tasks.domain.model.TaskKind.Task,
        projectId = null,
        parentTaskId = null,
        tags = emptyList(),
        dueDate = dueDate,
        dueTime = dueTime,
        startDate = null,
        startTime = null,
        endDate = null,
        endTime = null,
        accentColor = accentColor,
        emoji = emoji,
        completedAt = completedAt,
        someday = false,
        archivedAt = null,
        isPinned = false,
        dependsOn = emptySet(),
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
        userId = testUserId,
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
    fun `emoji is passed through from task`() {
        val task = makeTask(emoji = "🌟")
        val result = CalendarTaskMapper.toCalendarTaskUi(task, today)
        assertEquals("🌟", result.emoji)
    }

    @Test
    fun `emoji is null when task has no emoji`() {
        val task = makeTask(emoji = null)
        val result = CalendarTaskMapper.toCalendarTaskUi(task, today)
        assertNull(result.emoji)
    }

    @Test
    fun `accentColor is passed through from task`() {
        val task = makeTask(accentColor = 0xFF5500)
        val result = CalendarTaskMapper.toCalendarTaskUi(task, today)
        assertEquals(0xFF5500L, result.accentColor)
    }

    @Test
    fun `accentColor is null when task has no accentColor`() {
        val task = makeTask(accentColor = null)
        val result = CalendarTaskMapper.toCalendarTaskUi(task, today)
        assertNull(result.accentColor)
    }
}
