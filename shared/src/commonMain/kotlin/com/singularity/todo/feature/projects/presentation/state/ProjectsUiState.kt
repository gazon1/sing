package com.singularity.todo.feature.projects.presentation.state
import androidx.compose.runtime.Immutable

import com.singularity.todo.feature.projects.domain.model.ProjectWithCounts

enum class ProjectSortOrder { Name, Color }

sealed interface ProjectsUiState {
    data object Loading : ProjectsUiState
    data object Empty : ProjectsUiState
    data class Content(
        val projects: List<ProjectWithCounts>,
        val searchQuery: String = "",
        val sortOrder: ProjectSortOrder = ProjectSortOrder.Name,
    ) : ProjectsUiState
    data class Error(val message: String) : ProjectsUiState
}
