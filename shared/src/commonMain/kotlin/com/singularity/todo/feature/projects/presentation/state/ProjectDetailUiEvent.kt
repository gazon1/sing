package com.singularity.todo.feature.projects.presentation.state

import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * One-shot events emitted by [com.singularity.todo.feature.projects.presentation.viewmodel.ProjectDetailViewModel].
 */
sealed interface ProjectDetailUiEvent : MviEvent {
    data object NavigateBack : ProjectDetailUiEvent // after successful delete
    data class ShowError(val message: String) : ProjectDetailUiEvent
    data class UndoDeleteTask(val taskId: TaskId) : ProjectDetailUiEvent
}
