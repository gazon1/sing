package com.singularity.todo.feature.projects.presentation.state

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.domain.model.ProjectWithCounts

enum class ProjectSortOrder { Name, Color }

sealed interface ProjectsUiState {
    data object Loading : ProjectsUiState
    data class Empty(val userId: UserId) : ProjectsUiState
    data class Content(
        val projects: List<ProjectWithCounts>,
        val searchQuery: String = "",
        val sortOrder: ProjectSortOrder = ProjectSortOrder.Name,
    ) : ProjectsUiState
    data class Error(val message: String) : ProjectsUiState
}
