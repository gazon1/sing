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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
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

    @ParameterizedTest(name = "completedAt={0}, dueDate={1} → {2}")
    @MethodSource
    fun `status mapping`(completedAt: Instant?, dueDate: LocalDate, expectedStatus: CalendarTaskStatus) {
        val task = makeTask(completedAt = completedAt, dueDate = dueDate)
        assertEquals(expectedStatus, CalendarTaskMapper.toCalendarTaskUi(task, today).status)
    }

    // ─── field mapping ─────────────────────────────────────────────────────

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
        assertEquals(dueDate, CalendarTaskMapper.toCalendarTaskUi(task, today).date)
    }

    @Test
    fun `null dueDate falls back to today for date field`() {
        val task = makeTask(dueDate = null)
        assertEquals(today, CalendarTaskMapper.toCalendarTaskUi(task, today).date)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `isAllDay reflects presence of dueTime`(hasDueTime: Boolean) {
        val dueTime = if (hasDueTime) LocalTime(9, 0) else null
        val result = CalendarTaskMapper.toCalendarTaskUi(makeTask(dueTime = dueTime), today)
        assertEquals(!hasDueTime, result.isAllDay)
    }

    @Test
    fun `startTime is mapped from dueTime`() {
        val task = makeTask(dueTime = LocalTime(10, 30))
        assertEquals(LocalTime(10, 30), CalendarTaskMapper.toCalendarTaskUi(task, today).startTime)
    }

    @Test
    fun `endTime is always null in current mapping`() {
        val task = makeTask(dueTime = LocalTime(10, 30))
        assertNull(CalendarTaskMapper.toCalendarTaskUi(task, today).endTime)
    }

    // ─── isRecurring ───────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `isRecurring reflects parameter`(isRecurring: Boolean) {
        val result = CalendarTaskMapper.toCalendarTaskUi(makeTask(), today, isRecurring = isRecurring)
        assertEquals(isRecurring, result.isRecurring)
    }

    // ─── isLink ────────────────────────────────────────────────────────────

    @Test
    fun `isLink is false for Task kind`() {
        // Task kind defaults to Task — isLink reflects TaskKind.Note which we can't
        // create without importing TaskKind directly; verify current behaviour.
        val result = CalendarTaskMapper.toCalendarTaskUi(makeTask(), today)
        assertFalse(result.isLink)
    }

    // ─── emoji and accentColor ───────────────────────────────────────────────

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = ["🌟", "🔥", "📌"])
    fun `emoji passes through when present`(emoji: String?) {
        val result = CalendarTaskMapper.toCalendarTaskUi(makeTask(emoji = emoji), today)
        assertEquals(emoji, result.emoji)
    }

    @Test
    fun `accentColor is null when task has no accentColor`() {
        val task = makeTask(accentColor = null)
        assertNull(CalendarTaskMapper.toCalendarTaskUi(task, today).accentColor)
    }

    @ParameterizedTest
    @ValueSource(longs = [0xFF5500L, 0x000000L, 0xFFFFFFFFL])
    fun `accentColor passes through when present`(accentColor: Long) {
        val result = CalendarTaskMapper.toCalendarTaskUi(makeTask(accentColor = accentColor), today)
        assertEquals(accentColor, result.accentColor)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // companion object — all @MethodSource providers in one place
    // ═══════════════════════════════════════════════════════════════════════════
    companion object {
        @JvmStatic
        fun `status mapping`(): List<Arguments> = listOf(
            // today = Sept 16, 2026
            Arguments.of(Instant.fromEpochMilliseconds(1), LocalDate(2026, Month.SEPTEMBER, 10), CalendarTaskStatus.DONE),
            Arguments.of(null, LocalDate(2026, Month.SEPTEMBER, 10), CalendarTaskStatus.OVERDUE),
            Arguments.of(null, LocalDate(2026, Month.SEPTEMBER, 16), CalendarTaskStatus.PENDING),
            Arguments.of(null, LocalDate(2026, Month.SEPTEMBER, 20), CalendarTaskStatus.PENDING),
        )
    }
}
