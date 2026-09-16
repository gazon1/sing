package com.singularity.todo.feature.calendar.presentation.state

import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate

/**
 * One-shot user intents for the Calendar screen.
 * Maps to ViewModel actions — see [com.singularity.todo.feature.calendar.presentation.viewmodel.CalendarViewModel].
 */
sealed interface CalendarIntent {
    data class ViewModeChanged(val mode: CalendarViewMode) : CalendarIntent
    data object GoToday : CalendarIntent
    data object GoNext : CalendarIntent
    data object GoPrevious : CalendarIntent
    data class DayClicked(val date: LocalDate) : CalendarIntent
    data object ToggleMiniCalendar : CalendarIntent
    data class TaskClicked(val taskId: TaskId) : CalendarIntent
    data object DismissMiniCalendar : CalendarIntent
}
