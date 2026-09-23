package com.singularity.todo.feature.calendar.domain.model

import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * UI model for a task rendered in the Calendar screen.
 *
 * Derived from the domain [com.singularity.todo.feature.tasks.domain.model.Task]:
 * - [dueTime] is null → [isAllDay] = true
 * - [isCompleted] → [status] = DONE
 * - overdue (dueDate < today && !isCompleted) → [status] = OVERDUE
 * - [isRecurring] is derived from [ReminderRepository.watchByTask]
 * - [isLink] = kind == TaskKind.Note
 */
data class CalendarTaskUi(
    val id: TaskId,
    val title: String,
    val date: LocalDate,
    val isAllDay: Boolean = true,
    /** Start time from Task.dueTime. */
    val startTime: LocalTime? = null,
    /** End time from Task.endTime (active window end). Null if no end time set. */
    val endTime: LocalTime? = null,
    val status: CalendarTaskStatus = CalendarTaskStatus.PENDING,
    val isRecurring: Boolean = false,
    /** Notes (TaskKind.Note) are rendered as underlined links in the calendar. */
    val isLink: Boolean = false,
    /** Optional emoji shown as leading text in month-grid cells. From Task.emoji. */
    val emoji: String? = null,
    /**
     * Optional accent color (ARGB Long). From Task.accentColor.
     * Null means use the default calendar palette color.
     */
    val accentColor: Long? = null,
)
