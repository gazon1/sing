package com.singularity.todo.feature.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.settings.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ProjectsUiState {
    data object Loading : ProjectsUiState
    data class Empty(val userId: String) : ProjectsUiState
    data class Content(val projects: List<Project>) : ProjectsUiState
    data class Error(val message: String) : ProjectsUiState
}

class ProjectsViewModel(
    private val projectRepo: ProjectsRepository,
    private val createProject: CreateProjectUseCase,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val userId: Flow<String> = settingsRepository.userId

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ProjectsUiState> = userId
        .flatMapLatest { uid -> projectRepo.watchProjects(uid) }
        .map<List<Project>, ProjectsUiState> { projects ->
            if (projects.isEmpty()) ProjectsUiState.Empty("")
            else ProjectsUiState.Content(projects)
        }
        .catch { emit(ProjectsUiState.Error(it.message ?: "Error")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProjectsUiState.Loading)

    fun delete(id: ProjectId) = viewModelScope.launch {
        projectRepo.delete(id)
    }
}
