package com.singularity.todo.feature.calendar.presentation.state

import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * One-shot UI events emitted by [com.singularity.todo.feature.calendar.presentation.viewmodel.CalendarViewModel].
 * Consumed by [com.singularity.todo.core.ui.components.NotificationHost] in CalendarScreen.
 */
sealed interface CalendarUiEvent {
    /** Navigate to the task detail screen. */
    data class NavigateToTask(val taskId: TaskId) : CalendarUiEvent
    /** Show an error message. */
    data class ShowError(val message: String) : CalendarUiEvent
}
