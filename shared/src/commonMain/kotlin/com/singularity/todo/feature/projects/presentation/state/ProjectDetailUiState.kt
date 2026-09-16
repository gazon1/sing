package com.singularity.todo.feature.projects.presentation.state

import com.singularity.todo.feature.projects.presentation.model.ProjectDetailUi

sealed interface ProjectDetailUiState {
    data object Loading : ProjectDetailUiState
    data object NotFound : ProjectDetailUiState
    data class Content(val ui: ProjectDetailUi) : ProjectDetailUiState
}
