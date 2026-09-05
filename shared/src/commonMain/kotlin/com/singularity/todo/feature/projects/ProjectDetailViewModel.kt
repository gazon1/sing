package com.singularity.todo.feature.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Minimal ViewModel for the project-detail screen.
 *
 * Watches a single project (by id) and exposes [ProjectDetailUiState].
 *
 * Why a separate ViewModel: the existing [ProjectsViewModel] lists ALL
 * projects and watches tasks in parallel; this one only watches ONE
 * project. Different lifecycle, different state shape.
 */
class ProjectDetailViewModel(
    private val projectRepo: ProjectsRepository,
) : ViewModel() {

    private val projectId = MutableStateFlow<ProjectId?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ProjectDetailUiState> = projectId
        .flatMapLatest { id ->
            if (id == null) flowOf(ProjectDetailUiState.Empty)
            else projectRepo.watchProject(id).map { project ->
                if (project == null) ProjectDetailUiState.NotFound
                else ProjectDetailUiState.Content(project)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProjectDetailUiState.Loading)

    fun start(projectId: ProjectId) {
        this.projectId.value = projectId
    }
}

sealed interface ProjectDetailUiState {
    data object Loading : ProjectDetailUiState
    data object Empty : ProjectDetailUiState
    data object NotFound : ProjectDetailUiState
    data class Content(val project: Project) : ProjectDetailUiState
}
