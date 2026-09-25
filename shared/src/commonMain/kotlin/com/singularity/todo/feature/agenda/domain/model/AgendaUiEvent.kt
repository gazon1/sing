package com.singularity.todo.feature.agenda.domain.model

import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * One-shot UI events emitted by the Agenda screen.
 * Collected via [kotlinx.coroutines.flow.SharedFlow] in the composable.
 */
sealed interface AgendaUiEvent : MviEvent {
    data class NavigateToTask(val taskId: TaskId) : AgendaUiEvent
    data class ShowTaskContextMenu(val taskId: TaskId) : AgendaUiEvent
    data class ExpandTask(val taskId: TaskId) : AgendaUiEvent
}
