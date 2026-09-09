package com.singularity.todo.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.Project
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.projects.ProjectsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI state for [TasksByProjectScreen].
 */
sealed interface TasksByProjectUiState {
    data object Loading : TasksByProjectUiState
    data class Content(
        val project: Project,
        val tasks: List<Task>,
        val totalCount: Int,
        val completedCount: Int,
        val hideCompleted: Boolean,
    ) : TasksByProjectUiState
    data object ProjectNotFound : TasksByProjectUiState
}

/**
 * One-shot events for [TasksByProjectScreen].
 */
sealed interface TasksByProjectEvent {
    data object NavigateBack : TasksByProjectEvent
    data class ShowError(val message: String) : TasksByProjectEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class TasksByProjectViewModel(
    private val projectId: ProjectId,
    private val taskRepo: TaskRepository,
    projectRepo: ProjectsRepository,
    private val createTask: CreateTaskUseCase,
    private val updateTask: UpdateTaskUseCase,
    private val currentUser: ProfileAwareCurrentUser,
) : ViewModel() {

    private val _hideCompleted = MutableStateFlow(false)
    val hideCompleted: StateFlow<Boolean> = _hideCompleted

    private val projectFlow: StateFlow<Project?> = projectRepo.watchProject(projectId)
        .map { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val state: StateFlow<TasksByProjectUiState> = combine(
        projectFlow,
        projectFlow.flatMapLatest { project ->
            if (project == null) flowOf(emptyList())
            else taskRepo.watchTasks(
                UserId(project.userId),
                TaskFilter.ByProject(projectId)
            )
        },
        _hideCompleted,
    ) { project, allTasks, hideCompleted ->
        when {
            project == null -> TasksByProjectUiState.Loading
            else -> {
                val visible = if (hideCompleted) allTasks.filter { it.completedAt == null } else allTasks
                TasksByProjectUiState.Content(
                    project = project,
                    tasks = visible,
                    totalCount = allTasks.size,
                    completedCount = allTasks.count { it.completedAt != null },
                    hideCompleted = hideCompleted,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TasksByProjectUiState.Loading)

    fun toggleHideCompleted() {
        _hideCompleted.value = !_hideCompleted.value
    }

    fun addTask(title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val userId = UserId(currentUser.scopedUserId.value.value)
            createTask(
                CreateTaskInput(
                    title = title.trim(),
                    projectId = projectId,
                    userId = userId,
                )
            )
        }
    }

    fun toggleTask(taskId: TaskId) {
        viewModelScope.launch {
            val task = taskRepo.getById(taskId) ?: return@launch
            val completedAt = if (task.completedAt == null) kotlin.time.Clock.System.now() else null
            updateTask(taskId) { it.copy(completedAt = completedAt) }
        }
    }
}
