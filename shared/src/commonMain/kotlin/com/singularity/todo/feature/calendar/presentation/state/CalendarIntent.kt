package com.singularity.todo.feature.calendar.presentation.state

import com.singularity.todo.feature.calendar.domain.logic.YearMonth
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

    /**
     * Emitted by [com.singularity.todo.feature.calendar.presentation.components.calendar.MonthGridView]
     * when the [androidx.compose.foundation.pager.HorizontalPager] settles on a new month.
     *
     * Distinct from [GoNext]/[GoPrevious] (which step by one view-mode unit) — page
     * commits are reported in absolute [YearMonth] units regardless of view mode.
     */
    data class MonthPageChanged(val month: YearMonth) : CalendarIntent

    /**
     * Emitted when the user long-presses an empty cell in the month grid
     * (a day with no tasks) to create a new task for that date.
     */
    data class EmptyCellLongPressed(val date: LocalDate) : CalendarIntent
}
