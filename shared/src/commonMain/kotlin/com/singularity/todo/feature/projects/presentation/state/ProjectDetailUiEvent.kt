package com.singularity.todo.feature.projects.presentation.state

/**
 * One-shot events emitted by [com.singularity.todo.feature.projects.presentation.viewmodel.ProjectDetailViewModel].
 */
sealed interface ProjectDetailUiEvent {
    data object NavigateBack : ProjectDetailUiEvent // after successful delete
    data class ShowError(val message: String) : ProjectDetailUiEvent
}
