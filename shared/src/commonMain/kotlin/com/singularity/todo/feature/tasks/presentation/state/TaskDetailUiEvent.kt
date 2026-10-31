package com.singularity.todo.feature.tasks.presentation.state

import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * One-shot UI events from [TaskDetailCoordinator].
 */
sealed interface TaskDetailUiEvent : MviEvent {
    data class Error(val message: String) : TaskDetailUiEvent
    data class Saved(val message: String) : TaskDetailUiEvent
    data class UndoDelete(val taskId: TaskId) : TaskDetailUiEvent
    data class UndoArchive(val taskId: TaskId) : TaskDetailUiEvent

    /**
     * The whole series was deleted, and the user may want it back.
     *
     * @param taskIds The occurrences that went to the trash.
     * @param dates How many dates the series had — what the message says.
     */
    data class UndoDeleteSeries(val taskIds: List<TaskId>, val dates: Int) : TaskDetailUiEvent

    data object NavigateBack : TaskDetailUiEvent
}
