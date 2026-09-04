package com.singularity.todo.feature.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.ui.components.UiEvent
import com.singularity.todo.feature.ai.use_cases.ProjectReviewUseCase
import com.singularity.todo.feature.tasks.TaskFilter
import com.singularity.todo.feature.tasks.TaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
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
    private val currentUser: CurrentUser,
    private val taskRepository: TaskRepository,
    private val projectReview: ProjectReviewUseCase? = null
) : ViewModel() {

    private val userIdFlow = currentUser.userId

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ProjectsUiState> = userIdFlow
        .flatMapLatest { uid -> projectRepo.watchProjects(uid.value) }
        .map { projects ->
            if (projects.isEmpty()) ProjectsUiState.Empty("")
            else ProjectsUiState.Content(projects)
        }
        .catch { emit(ProjectsUiState.Error(it.message ?: "Error")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProjectsUiState.Loading)

    private val _aiResult = MutableSharedFlow<String>()
    val aiResult = _aiResult.asSharedFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    fun delete(id: ProjectId) = viewModelScope.launch {
        projectRepo.delete(id)
    }

    fun reviewProject(project: Project) = viewModelScope.launch {
        val uid = currentUser.current
        val tasks = taskRepository.watchTasks(uid, TaskFilter.ByProject(project.id)).first()
        val result = projectReview?.invoke(project.name, tasks.map { it.title })
            ?.fold(onSuccess = { it }, onFailure = { "Error: ${it.message ?: "Failed"}" })
            ?: "AI not available on Android"
        _aiResult.emit(result)
        _events.emit(UiEvent.ShowDialog(title = "Project Review", text = result))
    }
}
