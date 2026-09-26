package com.singularity.todo.feature.projects.presentation.viewmodel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.fireAndForget
import com.singularity.todo.core.database.toProject
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.ai.use_cases.ProjectReviewUseCase
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Intent for the projects list screen.
 */
sealed interface ProjectsIntent : MviIntent {
    data class SetSearchQuery(val query: String) : ProjectsIntent
    data class SetSortOrder(val order: ProjectSortOrder) : ProjectsIntent
    data class Delete(val id: ProjectId) : ProjectsIntent
    data class ReviewProject(val project: Project) : ProjectsIntent
}

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
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<ProjectsUiState, ProjectsIntent, ProjectsUiEvent>(
        initialState = ProjectsUiState.Loading,
        scope = scope,
    ) {
    override val vmScope = scope

    private val _searchQuery = MutableStateFlow("")
    private val _sortOrder = MutableStateFlow(ProjectSortOrder.Name)

    init {
        vmScope.launch {
            combine(_searchQuery, _sortOrder) { query, sort -> query to sort }
                .flatMapLatest { (query, sort) ->
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
                            ProjectsUiState.Empty
                        } else {
                            ProjectsUiState.Content(projects = sorted, searchQuery = query, sortOrder = sort)
                        }
                    }
                }
                .catch { cause ->
                    setState(ProjectsUiState.Error(cause.toMessage()))
                }
                .collect { setState(it) }
        }
    }

    override fun onIntent(intent: ProjectsIntent) {
        when (intent) {
            is ProjectsIntent.SetSearchQuery -> setSearchQuery(intent.query)
            is ProjectsIntent.SetSortOrder -> setSortOrder(intent.order)
            is ProjectsIntent.Delete -> delete(intent.id)
            is ProjectsIntent.ReviewProject -> reviewProject(intent.project)
        }
    }

    private fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private fun setSortOrder(order: ProjectSortOrder) {
        _sortOrder.value = order
    }

    private fun delete(id: ProjectId) {
        vmScope.fireAndForget(
            errorLabel = "Delete project failed",
            onError = { e ->
                vmScope.launch {
                    emit(ProjectsUiEvent.Error("Delete project failed: ${e.toMessage()}"))
                }
            },
        ) {
            deleteProject(id)
        }
    }

    private fun reviewProject(project: Project) {
        vmScope.launch {
            val tasks = taskRepository.observeByFilter(TaskFilter.ByProject(project.id)).first()
            val result = projectReview?.invoke(project.name, tasks.map { it.title })
                ?.fold(
                    onSuccess = { it },
                    onFailure = { "Error: ${it.toMessage()}" },
                )
                ?: "AI not available on Android"
            emit(ProjectsUiEvent.ProjectReviewResult(result))
        }
    }
}
