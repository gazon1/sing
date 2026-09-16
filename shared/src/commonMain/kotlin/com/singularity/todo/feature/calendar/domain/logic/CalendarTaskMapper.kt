package com.singularity.todo.feature.calendar.domain.logic

import com.singularity.todo.feature.calendar.domain.model.CalendarTaskStatus
import com.singularity.todo.feature.calendar.domain.model.CalendarTaskUi
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import kotlinx.datetime.LocalDate

/**
 * Pure transformations from domain [Task] to [CalendarTaskUi].
 *
 * No side effects, no dependencies on Compose or Android — fully testable.
 */
object CalendarTaskMapper {

    /**
     * Converts a [Task] to [CalendarTaskUi].
     *
     * @param task The domain task.
     * @param today Used to compute OVERDUE status.
     * @param isRecurring Whether this task has an active recurring reminder.
     */
    fun toCalendarTaskUi(
        task: Task,
        today: LocalDate,
        isRecurring: Boolean = false,
    ): CalendarTaskUi {
        val status = when {
            task.isCompleted -> CalendarTaskStatus.DONE
            task.dueDate != null && task.dueDate < today -> CalendarTaskStatus.OVERDUE
            else -> CalendarTaskStatus.PENDING
        }
        return CalendarTaskUi(
            id = task.id,
            title = task.title,
            date = task.dueDate ?: today,
            isAllDay = task.dueTime == null,
            startTime = task.dueTime,
            endTime = null,
            status = status,
            isRecurring = isRecurring,
            isLink = task.kind == TaskKind.Note,
            emoji = null, // enriched separately by CalendarViewModel if needed
            accentColor = null,
        )
    }
}
