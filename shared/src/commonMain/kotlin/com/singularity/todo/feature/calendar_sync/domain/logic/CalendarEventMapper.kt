package com.singularity.todo.feature.calendar_sync.domain.logic

import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncEvent
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.tasks.domain.model.Task
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Pure mapper: Task (+ optional Reminder) → [CalendarSyncEvent].
 *
 * Does NOT interact with the system calendar — only transforms domain objects
 * into the format required by [CalendarProviderPort].
 */
object CalendarEventMapper {

    /**
     * Maps [task] to a [CalendarSyncEvent] for [targetCalendarId].
     *
     * - If [reminder] is provided and has a [Reminder.recurringPattern],
     *   an RRULE is generated via [RruleGenerator].
     * - All-day vs timed is inferred from whether [task.dueTime] is set:
     *   - null → all-day event on [task.dueDate]
     *   - non-null → timed event; end = start + 1 hour
     * - If [task.dueDate] is null, the task syncs as an all-day event on today.
     */
    fun mapToEvent(
        task: Task,
        reminder: Reminder?,
        targetCalendarId: String,
        existingEventId: Long?,
    ): CalendarSyncEvent {
        val tz = TimeZone.currentSystemDefault()
        val effectiveDate = task.dueDate ?: todayInSystemZone(tz)

        val startMs = if (task.dueTime != null) {
            val ldt = LocalDateTime(
                year = effectiveDate.year,
                month = effectiveDate.month,
                dayOfMonth = effectiveDate.day,
                hour = task.dueTime.hour,
                minute = task.dueTime.minute,
                second = 0,
                nanosecond = 0,
            )
            ldt.toInstant(tz).toEpochMilliseconds()
        } else {
            effectiveDate.atStartOfDayIn(tz).toEpochMilliseconds()
        }

        val endMs = if (task.dueTime != null) {
            startMs + 3_600_000L // 1-hour duration
        } else {
            // All-day: end = start of next day
            val tomorrow = effectiveDate.nextDay()
            tomorrow.atStartOfDayIn(tz).toEpochMilliseconds()
        }

        val rrule = reminder?.recurringPattern?.let { pattern ->
            RecurrenceRuleMapper.map(pattern)?.let { rule ->
                RruleGenerator.generate(rule)
            }
        }

        // Strip any existing deep-link to avoid doubling on re-sync
        val rawDescription = task.description ?: ""
        val strippedDescription = rawDescription
            .substringBefore(CalendarSyncEvent.deepLink(task.id))
            .trimEnd()
        val description = buildString {
            if (strippedDescription.isNotBlank()) {
                append(strippedDescription)
                append("\n\n")
            }
            append(CalendarSyncEvent.deepLink(task.id))
        }

        val event = CalendarSyncEvent(
            taskId = task.id,
            calendarId = targetCalendarId,
            eventId = existingEventId,
            title = task.title,
            description = description,
            startMs = startMs,
            endMs = endMs,
            allDay = task.dueTime == null,
            rrule = rrule,
            color = task.accentColor,
        )
        return event.copy(checksum = event.checksum())
    }

    private fun todayInSystemZone(tz: TimeZone): LocalDate = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        .toLocalDateTime(tz).date

    private fun LocalDate.nextDay(): LocalDate {
        val dim = daysInMonth(year, monthNumber)
        val nextD = day + 1
        return when {
            nextD <= dim -> LocalDate(year, monthNumber, nextD)
            monthNumber == 12 -> LocalDate(year + 1, 1, 1)
            else -> LocalDate(year, monthNumber + 1, 1)
        }
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeapYear(year)) 29 else 28
        else -> 30
    }

    private fun isLeapYear(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
}
