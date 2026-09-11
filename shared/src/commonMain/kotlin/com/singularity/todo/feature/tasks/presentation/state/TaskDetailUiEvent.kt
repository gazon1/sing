package com.singularity.todo.feature.tasks.presentation.state

import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * One-shot UI events from TaskDetailViewModel.
 */
sealed interface TaskDetailUiEvent {
    data class Error(val message: String) : TaskDetailUiEvent
    data class Saved(val message: String) : TaskDetailUiEvent
    data class UndoDelete(val taskId: TaskId) : TaskDetailUiEvent
    data object NavigateBack : TaskDetailUiEvent
}
