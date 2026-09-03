package com.singularity.todo.feature.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

sealed interface ProjectsUiState {
    data object Loading : ProjectsUiState
    data class Empty(val userId: String) : ProjectsUiState
    data class Content(val projects: List<Project>) : ProjectsUiState
    data class Error(val message: String) : ProjectsUiState
}

class ProjectsViewModel(
    private val getProjects: GetProjectsUseCase,
    private val deleteProject: DeleteProjectUseCase,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val currentUserId = runBlocking { settingsRepository.userId.first() }

    val state: StateFlow<ProjectsUiState> = getProjects(currentUserId)
        .map<List<Project>, ProjectsUiState> { projects ->
            if (projects.isEmpty()) ProjectsUiState.Empty(currentUserId)
            else ProjectsUiState.Content(projects)
        }
        .catch { emit(ProjectsUiState.Error(it.message ?: "Error")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProjectsUiState.Loading)

    fun delete(id: ProjectId) = viewModelScope.launch {
        deleteProject(id)
    }
}
