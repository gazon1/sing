package com.singularity.todo.feature.projects.presentation.viewmodel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.database.toProject
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.llm.AI_NOT_CONFIGURED
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.search.SearchMode
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.core.text.FieldOwnedText
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Intent for the projects list screen.
 */
sealed interface ProjectsIntent : MviIntent {
    data class SetSearchQuery(val query: String) : ProjectsIntent
    data class SetSortOrder(val order: ProjectSortOrder) : ProjectsIntent
    data class Delete(val id: ProjectId) : ProjectsIntent

    /**
     * The user opened a closed project again: its work is not finished after all.
     *
     * The reverse of closing, and as narrow — a project that was closed by
     * mistake has to be reachable, or the mistake is permanent.
     */
    data class Reopen(val id: ProjectId) : ProjectsIntent
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
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectsViewModel(
    private val projectRepo: ProjectsRepository,
    private val taskRepository: TaskRepository,
    private val projectReview: ProjectReviewUseCase? = null,
    private val deleteProject: DeleteProjectUseCase,
    private val clock: Clock = Clock.System,
    crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<ProjectsUiState, ProjectsIntent, ProjectsUiEvent>(
        initialState = ProjectsUiState.Loading(
            searchQuery = FieldOwnedText.empty(),
            sortOrder = ProjectSortOrder.Name,
            closedProjects = emptyList(),
        ),
        crashReporter = crashReporter,
        scope = scope,
    ) {

    /**
     * The query the list is filtered by.
     *
     * It is a [FieldOwnedText]: the only write is the field's own text coming back,
     * and the write says so.
     */
    private val _searchQuery = MutableStateFlow(FieldOwnedText.empty())
    private val _sortOrder = MutableStateFlow(ProjectSortOrder.Name)

    init {
        vmScope.launch {
            combine(_searchQuery, _sortOrder) { query, sort -> query to sort }.flatMapLatest { (query, sort) ->
                combine(
                    projectRepo.observeProjectsWithCounts(),
                    projectRepo.observeClosed(),
                ) { rows, closed -> rows to closed }
                    .map { (rows, closed) ->
                        val domainRows = rows.map { row ->
                            ProjectWithCounts(
                                project = row.project.toProject(),
                                totalCount = row.totalCount,
                                completedCount = row.completedCount,
                            )
                        }
                        val filtered = if (query.text.isBlank()) {
                            domainRows
                        } else {
                            domainRows.filter { PROJECT_LIST_SEARCH_MODE.matches(it.project.name, query.text) }
                        }
                        val sorted = when (sort) {
                            ProjectSortOrder.Name -> filtered.sortedBy { it.project.name }
                            ProjectSortOrder.Color -> filtered.sortedBy { it.project.color }
                        }
                        if (sorted.isEmpty()) {
                            // The query and the sort order travel with the empty state:
                            // they describe the filter that produced it, and the screen
                            // hands the query straight back to the search field. A state
                            // that dropped the query would make the field look empty while
                            // the ViewModel still filtered by the old one — and the list
                            // could never come back.
                            ProjectsUiState.Empty(
                                searchQuery = query,
                                sortOrder = sort,
                                closedProjects = closed,
                            )
                        } else {
                            ProjectsUiState.Content(
                                projects = sorted,
                                searchQuery = query,
                                sortOrder = sort,
                                closedProjects = closed,
                            )
                        }
                    }
            }
                .catch { cause ->
                    // The failure carries the query and the sort order: the screen still
                    // shows the search field and the sort control, and a state that
                    // reset them would clear what the user had typed. The closed
                    // projects travel with it for the same reason: they are on the
                    // screen, and a failure is not a reason to take them away.
                    setState(
                        ProjectsUiState.Error(
                            message = cause.toMessage(),
                            searchQuery = _searchQuery.value,
                            sortOrder = _sortOrder.value,
                            closedProjects = closedProjectsOnScreen(),
                        ),
                    )
                }
                .collect { setState(it) }
        }
    }

    /**
     * The closed projects the screen is showing right now.
     *
     * A failure is not a reason to take them away: they are still closed, and the
     * way back to them is still there.
     */
    private fun closedProjectsOnScreen(): List<Project> = when (val state = currentState) {
        is ProjectsUiState.Content -> state.closedProjects
        is ProjectsUiState.Empty -> state.closedProjects
        else -> emptyList()
    }

    override fun onIntent(intent: ProjectsIntent) {
        when (intent) {
            is ProjectsIntent.SetSearchQuery -> setSearchQuery(intent.query)
            is ProjectsIntent.SetSortOrder -> setSortOrder(intent.order)
            is ProjectsIntent.Delete -> delete(intent.id)
            is ProjectsIntent.Reopen -> reopen(intent.id)
            is ProjectsIntent.ReviewProject -> reviewProject(intent.project)
        }
    }

    /**
     * Records what the user typed in the search field.
     *
     * The field's own text: the field wrote it, and the field already shows it. So it
     * is an echo, and the value is written under that name.
     */
    private fun setSearchQuery(query: String) {
        _searchQuery.value = _searchQuery.value.echoed(query)
    }

    private fun setSortOrder(order: ProjectSortOrder) {
        _sortOrder.value = order
    }

    private fun delete(id: ProjectId) {
        emitError("Delete project failed", { msg -> ProjectsUiEvent.Error("Delete project failed: $msg") }) {
            deleteProject(id)
        }
    }

    /**
     * Opens a closed project again.
     *
     * The mirror of closing: the project leaves the list when it is closed and
     * comes back when it is opened. A project closed by mistake has to be
     * reachable, or the mistake is permanent — and the only way back would be
     * through one of its tasks, which a project without tasks does not have.
     */
    private fun reopen(id: ProjectId) {
        emitError("Open project failed", { msg -> ProjectsUiEvent.Error("Open project failed: $msg") }) {
            projectRepo.reopen(id, clock.now().toEpochMilliseconds())
        }
    }

    private fun reviewProject(project: Project) {
        vmScope.launch {
            val tasks = taskRepository.observeByFilter(TaskFilter.ByProject(project.id))
                .first()
            val result = projectReview?.invoke(project.name, tasks.map { it.title })
                ?.fold(
                    onSuccess = { it },
                    onFailure = { "Error: ${it.toMessage()}" },
                )
                ?: AI_NOT_CONFIGURED
            emit(ProjectsUiEvent.ProjectReviewResult(result))
        }
    }

    companion object {
        /**
         * The mode the project list searches in.
         *
         * The list is a surface, and a surface states the mode it searches in
         * (F8). This one filters the rows it already holds, and the term may
         * occur anywhere in the project's name — so it states the contains
         * mode. The switch belongs to the search screen; a list that has no
         * switch states the mode it uses, and that is a decision, not an
         * accident.
         *
         * The contains mode folds the diacritics the way the index folds them
         * (F7), so a search for "cafe" finds "Café" here as well.
         */
        val PROJECT_LIST_SEARCH_MODE: SearchMode = SearchMode.Contains
    }
}
