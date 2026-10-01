package com.singularity.todo.feature.calendar_sync.domain.logic

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderType
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarEventMapperTest {

    private val tz = TimeZone.currentSystemDefault()
    private fun today(): LocalDate {
        val nowMs = System.currentTimeMillis()
        return kotlin.time.Instant.fromEpochMilliseconds(nowMs)
            .toLocalDateTime(tz).date
    }

    private fun localDate(year: Int, month: Int, day: Int) =
        LocalDate(year, month, day)

    private fun localTime(hour: Int, minute: Int) =
        LocalTime(hour, minute)

    private fun midnightMs(date: LocalDate) =
        date.atStartOfDayIn(tz)
            .toEpochMilliseconds()

    private fun nextDayMidnightMs(date: LocalDate): Long {
        // Mirror the logic from CalendarEventMapper.LocalDate.nextDay()
        val dim = daysInMonth(date.year, date.month.number)
        val nextD = date.day + 1
        val tomorrow = when {
            nextD <= dim -> LocalDate(date.year, date.month.number, nextD)
            date.month.number == 12 -> LocalDate(date.year + 1, 1, 1)
            else -> LocalDate(date.year, date.month.number + 1, 1)
        }
        return tomorrow.atStartOfDayIn(tz)
            .toEpochMilliseconds()
    }

    private fun daysInMonth(year: Int, month: Int): Int =
        when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (isLeapYear(year)) 29 else 28
            else -> 30
        }

    private fun isLeapYear(year: Int): Boolean =
        year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

    private fun instantOfEpochMs(ms: Long): kotlin.time.Instant =
        kotlin.time.Instant.fromEpochMilliseconds(ms)

    // Full Task constructor with all required fields
    private fun task(
        id: TaskId,
        title: String,
        dueDate: LocalDate? = null,
        dueTime: LocalTime? = null,
        description: String? = null,
    ) =
        Task(
            id = id,
            title = title,
            description = description,
            priority = TaskPriority.None,
            kind = TaskKind.Task,
            projectId = null,
            parentTaskId = null,
            tags = emptyList(),
            dueDate = dueDate,
            dueTime = dueTime,
            startDate = null,
            startTime = null,
            endDate = null,
            endTime = null,
            accentColor = null,
            emoji = null,
            completedAt = null,
            someday = false,
            archivedAt = null,
            isPinned = false,
            dependsOn = emptySet(),
            createdAt = instantOfEpochMs(0),
            updatedAt = instantOfEpochMs(0),
            userId = UserId("u1"),
        )

    // Full Reminder constructor for recurring pattern tests
    private fun reminder(taskId: TaskId, recurringPattern: String?) =
        Reminder(
            id = ReminderId("r1"),
            taskId = taskId,
            userId = UserId("u1"),
            type = ReminderType.Gentle,
            offsetMinutes = 0,
            fireAt = 0L,
            recurringPattern = recurringPattern,
            viewId = null,
            lastFiredAt = null,
        )

    @Test
    fun timed_task_maps_to_timed_event_with_1h_duration() {
        val date = localDate(2026, 9, 22)
        val time = localTime(15, 0)
        val startMs = LocalDateTime(date.year, date.month.number, date.day, 15, 0, 0, 0).toInstant(tz)
            .toEpochMilliseconds()
        val t = task(TaskId("t1"), "Meeting", date, time)

        val result = CalendarEventMapper.mapToEvent(t, null, "cal1", null)

        assertEquals(false, result.allDay)
        assertEquals(startMs, result.startMs)
        assertEquals(startMs + 3_600_000L, result.endMs) // +1 hour
        assertEquals("cal1", result.calendarId)
    }

    @Test
    fun all_day_task_maps_to_all_day_event_ending_next_midnight() {
        val date = localDate(2026, 9, 22)
        val t = task(TaskId("t1"), "All day", date, null)

        val result = CalendarEventMapper.mapToEvent(t, null, "cal1", null)

        assertEquals(true, result.allDay)
        assertEquals(midnightMs(date), result.startMs)
        assertEquals(nextDayMidnightMs(date), result.endMs)
    }

    @Test
    fun null_due_date_uses_today() {
        val t = task(TaskId("t1"), "No date", null, null)

        val result = CalendarEventMapper.mapToEvent(t, null, "cal1", null)

        assertEquals(true, result.allDay)
        assertEquals(midnightMs(today()), result.startMs)
        assertEquals(nextDayMidnightMs(today()), result.endMs)
    }

    @Test
    fun recurring_task_maps_rrule() {
        val date = localDate(2026, 9, 22)
        val t = task(TaskId("t1"), "Daily", date)
        val r = reminder(TaskId("t1"), "DAILY")

        val result = CalendarEventMapper.mapToEvent(t, r, "cal1", null)

        assertEquals("FREQ=DAILY", result.rrule)
    }

    @Test
    fun description_with_embedded_deeplink_is_sanitized() {
        // If the task description already contains a deep-link from a previous sync,
        // it should be stripped before appending the new one (to avoid doubling)
        val date = localDate(2026, 9, 22)
        val t = task(
            TaskId("t1"),
            "Task",
            date,
            null,
            "Some text\n\nsingularity://task/t1\n\nMore text",
        )

        val result = CalendarEventMapper.mapToEvent(t, null, "cal1", null)

        // The mapper should strip any existing singularity://task/{id} before appending
        val deepLinkCount = result.description.split("singularity://task/").size - 1
        assertEquals(1, deepLinkCount, "Should have exactly one deep-link after stripping")
    }

    @Test
    fun deep_link_shape_is_singularity_task_id() {
        val date = localDate(2026, 9, 22)
        val t = task(TaskId("my-task-123"), "T", date)

        val result = CalendarEventMapper.mapToEvent(t, null, "cal1", null)

        assertEquals(
            "singularity://task/my-task-123",
            result.description.lines()
                .last { it.startsWith("singularity://") },
        )
    }
}
