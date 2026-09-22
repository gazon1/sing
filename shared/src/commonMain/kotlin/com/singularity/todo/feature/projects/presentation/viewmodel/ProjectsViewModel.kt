package com.singularity.todo.feature.projects.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.database.toProject
import com.singularity.todo.feature.ai.use_cases.ProjectReviewUseCase
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.model.ProjectWithCounts
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.projects.presentation.state.ProjectSortOrder
import com.singularity.todo.feature.projects.presentation.state.ProjectsUiEvent
import com.singularity.todo.feature.projects.presentation.state.ProjectsUiState
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.fireAndForget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Projects list screen ViewModel.
 *
 * Owns: project list with task counts, sort/filter state, AI project review.
 * Triggers: sort order changes, project create/delete/restore, AI review request.
 * One-shot events: [ProjectsUiEvent.NavigateToProject], [ProjectsUiEvent.NavigateToCreate],
 *   [ProjectsUiEvent.ShowError].
 *
 * @see ProjectsUiState
 * @see ProjectsUiEvent
 */
class ProjectsViewModel(
    private val projectRepo: ProjectsRepository,
    private val taskRepository: TaskRepository,
    private val projectReview: ProjectReviewUseCase? = null,
    private val deleteProject: DeleteProjectUseCase,
    private val currentUser: ProfileAwareCurrentUser,
    sharingStarted: () -> SharingStarted = { SharingStarted.WhileSubscribed(5000) },
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {

    init {
        addCloseable(scope)
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _sortOrder = MutableStateFlow(ProjectSortOrder.Name)
    val sortOrder: StateFlow<ProjectSortOrder> = _sortOrder

    /** userId for mutations — read once at call time (caller-trust). */
    private val userId get() = currentUser.scopedUserId.value

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ProjectsUiState> = combine(
        _searchQuery,
        _sortOrder,
    ) { query, sort ->
        query to sort
    }.flatMapLatest { (query, sort) ->
        projectRepo.observeProjectsWithCounts().map { rows ->
            val domainRows = rows.map { row ->
                ProjectWithCounts(
                    project = row.project.toProject(),
                    totalCount = row.totalCount,
                    completedCount = row.completedCount,
                )
            }
            val filtered = if (query.isBlank()) {
                domainRows
            } else {
                domainRows.filter { it.project.name.contains(query, ignoreCase = true) }
            }
            val sorted = when (sort) {
                ProjectSortOrder.Name -> filtered.sortedBy { it.project.name }
                ProjectSortOrder.Color -> filtered.sortedBy { it.project.color }
            }
            if (sorted.isEmpty()) {
                ProjectsUiState.Empty(userId)
            } else {
                ProjectsUiState.Content(projects = sorted, searchQuery = query, sortOrder = sort)
            }
        }
    }.catch { cause ->
        emit(ProjectsUiState.Error(cause.message ?: "Error"))
    }.stateIn(scope, sharingStarted(), ProjectsUiState.Loading)

    private val _aiResult = MutableSharedFlow<String>()

    private val _events = Channel<ProjectsUiEvent>(Channel.BUFFERED)
    val events: kotlinx.coroutines.flow.Flow<ProjectsUiEvent> = _events.receiveAsFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortOrder(order: ProjectSortOrder) {
        _sortOrder.value = order
    }

    fun delete(id: ProjectId) {
        scope.fireAndForget(
            errorLabel = "Delete project failed",
            onError = { e -> scope.launch { _events.trySend(ProjectsUiEvent.Error("Delete project failed: ${e.message ?: "unknown"}")) } },
        ) {
            deleteProject(id)
        }
    }

    fun reviewProject(project: Project) = scope.launch {
        val tasks = taskRepository.observeByFilter(TaskFilter.ByProject(project.id)).first()
        val result = projectReview?.invoke(project.name, tasks.map { it.title })
            ?.fold(
                onSuccess = { it },
                onFailure = { "Error: ${it.message ?: "Failed"}" },
            )
            ?: "AI not available on Android"
        _aiResult.emit(result)
        _events.trySend(ProjectsUiEvent.ProjectReviewResult(result))
    }
}
