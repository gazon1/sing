package com.singularity.todo.feature.tasks.domain.port

import com.singularity.todo.core.reminders.ReminderOffset
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

/**
 * Computes the instant at which a reminder should fire for a task with the given
 * due date/time, applying the [ReminderOffset] offset.
 *
 * Pure: depends only on its inputs, no side effects, no `Clock.now()`.
 *
 * @param dueDate  the task's due date, or null if no date is set.
 * @param dueTime  the task's due time, or null (defaults to 12:00).
 * @param offset   how many minutes before the due moment to fire.
 * @param zone     the time zone to use for date→instant conversion.
 * @return epoch milliseconds at which the reminder should fire.
 */
internal fun dueInstant(
    dueDate: LocalDate?,
    dueTime: LocalTime?,
    offset: ReminderOffset,
    zone: TimeZone,
    nowEpochMs: Long = System.currentTimeMillis(),
): Long {
    if (dueDate == null) return nowEpochMs
    val hour = dueTime?.hour ?: 12
    val minute = dueTime?.minute ?: 0
    val ldt = LocalDateTime(dueDate.year, dueDate.month, dueDate.day, hour, minute)
    val base = ldt.toInstant(zone).toEpochMilliseconds()
    return base - offset.minutes * 60_000L
}
