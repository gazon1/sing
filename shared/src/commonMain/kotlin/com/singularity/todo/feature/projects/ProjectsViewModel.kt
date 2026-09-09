package com.singularity.todo.feature.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.ai.use_cases.ProjectReviewUseCase
import com.singularity.todo.feature.projects.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.tasks.TaskFilter
import com.singularity.todo.feature.tasks.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ProjectSortOrder { Name, Color }

sealed interface ProjectsUiState {
    data object Loading : ProjectsUiState
    data class Empty(val userId: String) : ProjectsUiState
    data class Content(
        val projects: List<ProjectWithCounts>,
        val searchQuery: String = "",
        val sortOrder: ProjectSortOrder = ProjectSortOrder.Name,
    ) : ProjectsUiState
    data class Error(val message: String) : ProjectsUiState
}

class ProjectsViewModel(
    private val projectRepo: ProjectsRepository,
    private val createProject: CreateProjectUseCase,
    private val currentUser: ProfileAwareCurrentUser,
    private val taskRepository: TaskRepository,
    private val projectReview: ProjectReviewUseCase? = null,
    private val deleteProject: DeleteProjectUseCase,
    private val scopeOverride: CoroutineScope? = null,
    sharingStarted: () -> SharingStarted = { SharingStarted.WhileSubscribed(5000) },
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _sortOrder = MutableStateFlow(ProjectSortOrder.Name)
    val sortOrder: StateFlow<ProjectSortOrder> = _sortOrder

    private val userIdFlow = currentUser.scopedUserId

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ProjectsUiState> = combine(
        userIdFlow,
        _searchQuery,
        _sortOrder,
    ) { uid, query, sort ->
        Triple(uid, query, sort)
    }.flatMapLatest { (uid, query, sort) ->
        projectRepo.watchProjectsWithCounts(uid.value).map { rows ->
            val domainRows = rows.map { row ->
                ProjectWithCounts(
                    project = row.project.toProject(),
                    totalCount = row.totalCount,
                    completedCount = row.completedCount,
                )
            }
            val filtered = if (query.isBlank()) domainRows
            else domainRows.filter { it.project.name.contains(query, ignoreCase = true) }
            val sorted = when (sort) {
                ProjectSortOrder.Name -> filtered.sortedBy { it.project.name }
                ProjectSortOrder.Color -> filtered.sortedBy { it.project.color }
            }
            if (sorted.isEmpty()) ProjectsUiState.Empty(uid.value)
            else ProjectsUiState.Content(projects = sorted, searchQuery = query, sortOrder = sort)
        }
    }.catch { cause ->
        emit(ProjectsUiState.Error(cause.message ?: "Error"))
    }.stateIn(scope, sharingStarted(), ProjectsUiState.Loading)

    private val _aiResult = MutableSharedFlow<String>()
    val aiResult = _aiResult.asSharedFlow()

    private val _events = MutableSharedFlow<ProjectsUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ProjectsUiEvent> = _events.asSharedFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortOrder(order: ProjectSortOrder) {
        _sortOrder.value = order
    }

    fun delete(id: ProjectId) = scope.launch {
        val userId = currentUser.current.value
        deleteProject(id, userId)
    }

    fun reviewProject(project: Project) = scope.launch {
        val uid = currentUser.current
        val tasks = taskRepository.watchTasks(uid, TaskFilter.ByProject(project.id)).first()
        val result = projectReview?.invoke(project.name, tasks.map { it.title })
            ?.fold(
                onSuccess = { it },
                onFailure = { "Error: ${it.message ?: "Failed"}" },
            )
            ?: "AI not available on Android"
        _aiResult.emit(result)
        _events.emit(ProjectsUiEvent.ProjectReviewResult(result))
    }
}
