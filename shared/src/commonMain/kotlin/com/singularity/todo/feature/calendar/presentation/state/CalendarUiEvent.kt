package com.singularity.todo.feature.calendar.presentation.state

import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate

/**
 * One-shot UI events emitted by [com.singularity.todo.feature.calendar.presentation.viewmodel.CalendarViewModel].
 * Consumed by [com.singularity.todo.core.ui.components.NotificationHost] in CalendarScreen.
 */
sealed interface CalendarUiEvent : MviEvent {
    /** Navigate to the task detail screen. */
    data class NavigateToTask(val taskId: TaskId) : CalendarUiEvent

    /** Show the create-task bottom sheet pre-filled with [initialDueDate]. */
    data class ShowCreateTaskSheet(val initialDueDate: LocalDate) : CalendarUiEvent

    /** Show an error message. */
    data class ShowError(val message: String) : CalendarUiEvent

    /**
     * Open the create editor on the draft that holds the picked days.
     *
     * The event carries the *key* of the draft, not the days: the days are in
     * the draft, and the draft is the only channel between the two screens.
     */
    data class NavigateToCreateWithDates(val draftKey: String) : CalendarUiEvent
}
