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
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val status: CalendarTaskStatus = CalendarTaskStatus.PENDING,
    val isRecurring: Boolean = false,
    /** Notes (TaskKind.Note) are rendered as underlined links in the calendar. */
    val isLink: Boolean = false,
    /** Optional emoji shown as leading text in month-grid cells. */
    val emoji: String? = null,
    /** Optional accent color (ARGB Long). Currently unused — kept for future use. */
    val accentColor: Long? = null,
)
