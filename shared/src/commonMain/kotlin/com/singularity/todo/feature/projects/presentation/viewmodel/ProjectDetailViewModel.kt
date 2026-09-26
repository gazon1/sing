package com.singularity.todo.feature.projects.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.fireAndForget
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.core.ui.debounce.Debouncer
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.projects.domain.usecase.UpdateProjectUseCase
import com.singularity.todo.feature.projects.presentation.model.ParentOption
import com.singularity.todo.feature.projects.presentation.model.ProjectDetailUi
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailIntent
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailUiEvent
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailUiState
import com.singularity.todo.feature.tasks.domain.model.CreateTaskInput
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskUseCase
import com.singularity.todo.feature.tasks.domain.usecase.UpdateTaskUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * ViewModel for [com.singularity.todo.feature.projects.presentation.screen.ProjectDetailScreen].
 *
 * Combines the project, its tasks, and aggregate counts into a single [ProjectDetailUi].
 * Inline edits (name, description) use silent debounce — they update [_lastEditedAt]
 * but do NOT emit [ProjectDetailUiEvent.Saved].
 *
 * [_lastEditedAt] is a continuous state exposed as [lastEditedAt] for the screen
 * to render "Saved X ago" via [formatSavedRelative].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectDetailViewModel(
    private val projectId: ProjectId,
    private val projectRepo: ProjectsRepository,
    private val taskRepo: TaskRepository,
    private val deleteProject: DeleteProjectUseCase,
    private val updateProject: UpdateProjectUseCase,
    private val updateTask: UpdateTaskUseCase,
    private val createTaskUseCase: CreateTaskUseCase,
    private val clock: Clock,
    private val log: Logger,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<ProjectDetailUiState, ProjectDetailIntent.Domain, ProjectDetailUiEvent>(
    initialState = ProjectDetailUiState.Loading,
    scope = scope,
) {
    override val vmScope = scope

    // ─── UI State ───────────────────────────────────────────────────────────────

    private val _hideCompleted = MutableStateFlow(false)
    val hideCompleted: StateFlow<Boolean> = _hideCompleted

    /** Emits null on start (loading placeholder), then the project flow. */
    private val _projectFlow = MutableStateFlow<Project?>(null)
    private val projectFlow: StateFlow<Project?> = _projectFlow

    /**
     * Reactive list of parent-picker options, derived from [projectFlow] and
     * [ProjectsRepository.watchProjects]. Excludes the current project (cycle prevention)
     * and already-deleted / non-root projects.
     */
    private val _parentOptionsFlow = MutableStateFlow<List<ParentOption>>(emptyList())
    val parentOptionsFlow: StateFlow<List<ParentOption>> = _parentOptionsFlow

    /**
     * All active tasks that are NOT in this project — for the "add existing task"
     * quick-add picker. Excludes inbox tasks (projectId == null) and completed tasks.
     * Sorted by dueDate ascending (nulls last), then updatedAt descending.
     */
    private val _availableTasksFlow = MutableStateFlow<List<Task>>(emptyList())
    val availableTasksFlow: StateFlow<List<Task>> = _availableTasksFlow

    init {
        // Collect projectFlow
        vmScope.launch {
            projectRepo.observe(projectId)
                .onStart { emit(null) }
                .collect { _projectFlow.value = it }
        }

        // Collect parentOptionsFlow
        vmScope.launch {
            combine(
                projectRepo.observe(projectId).onStart { emit(null) },
                projectRepo.observeAll(),
            ) { project, allProjects ->
                if (project == null) {
                    emptyList()
                } else {
                    allProjects
                        .filter { it.id != project.id && it.parentId == null && !it.isDeleted }
                        .map { ParentOption(it.id, it.name, it.id == project.parentId) }
                }
            }.collect { _parentOptionsFlow.value = it }
        }

        // Collect availableTasksFlow
        vmScope.launch {
            taskRepo.observeByFilter(TaskFilter.All)
                .map { all ->
                    all
                        .filter { it.projectId != null && it.projectId != projectId && it.completedAt == null }
                        .sortedWith(
                            compareBy<Task, kotlinx.datetime.LocalDate?>(nullsLast()) { it.dueDate }
                                .thenByDescending { it.updatedAt },
                        )
                }
                .collect { _availableTasksFlow.value = it }
        }

        // Collect state
        vmScope.launch {
            combine(
                projectRepo.observe(projectId).onStart { emit(null) },
                projectRepo.observe(projectId).onStart { emit(null) }.flatMapLatest { project ->
                    if (project == null) {
                        flowOf(
                            emptyList(),
                        )
                    } else {
                        taskRepo.observeByFilter(TaskFilter.ByProject(projectId))
                    }
                },
                projectRepo.observe(projectId).onStart { emit(null) }.flatMapLatest { project ->
                    if (project == null) flowOf(emptyList()) else projectRepo.observeChildrenOf(projectId)
                },
                projectRepo.observe(projectId).onStart { emit(null) }.flatMapLatest { p ->
                    if (p == null || p.parentId == null) flowOf(null) else projectRepo.observe(p.parentId)
                },
                _hideCompleted,
            ) { project, tasks, childProjects, parent, hideCompleted ->
                _latestProject.value = project
                when {
                    project == null -> ProjectDetailUiState.Loading

                    project.isDeleted -> ProjectDetailUiState.NotFound

                    else -> {
                        draftState.seed(project.name, project.description ?: "")
                        val visibleTasks = if (hideCompleted) tasks.filter { it.completedAt == null } else tasks
                        ProjectDetailUiState.Content(
                            ProjectDetailUi(
                                project = project,
                                tasks = visibleTasks.take(5),
                                totalCount = tasks.size,
                                completedCount = tasks.count { it.completedAt != null },
                                childProjects = childProjects,
                                parent = parent,
                            ),
                        )
                    }
                }
            }.collect { setState(it) }
        }
    }

    // ─── Silent debounce for inline edits ───────────────────────────────────────

    private val _lastEditedAt = MutableStateFlow<Instant?>(null)
    val lastEditedAt: StateFlow<Instant?> = _lastEditedAt

    /** Draft state — single source of truth for editable name/description. */
    val draftState = ProjectDetailDraftState()

    /** Debouncer for silent inline edits (name, description). */
    private val debouncer = Debouncer(scope, 300.milliseconds)

    init {
        // Name debounce — mutate reads _latestProject inside fireAndForget to avoid TOCTOU.
        debouncer.debounce(draftState.state.map { it.name }) { name ->
            mutate { copy(name = name) }
        }
        // Description debounce — same pattern.
        debouncer.debounce(draftState.state.map { it.description }) { desc ->
            mutate { copy(description = desc) }
        }
    }

    // ─── Cached latest project — TOCTOU guard ──────────────────────────────────

    private val _latestProject = MutableStateFlow<Project?>(null)

    // ─── Intent dispatcher ─────────────────────────────────────────────────────

    /**
     * Единственный публичный метод для всех доменных операций.
     *
     * Routing-интенты ([ProjectDetailIntent.Routing]) обрабатываются экраном
     * и сюда не попадают.
     */
    override fun onIntent(intent: ProjectDetailIntent.Domain) {
        when (intent) {
            // ── Visibility ──────────────────────────────────────────────────
            is ProjectDetailIntent.Domain.ToggleHideCompleted ->
                _hideCompleted.value = !_hideCompleted.value

            // ── Inline edits — debounced, written to draft StateFlows ────────
            is ProjectDetailIntent.Domain.UpdateName ->
                draftState.setName(intent.name)

            is ProjectDetailIntent.Domain.UpdateDescription ->
                draftState.setDescription(intent.description ?: "")

            // ── Pickers ─────────────────────────────────────────────────────
            is ProjectDetailIntent.Domain.UpdateColor -> {
                mutate { copy(color = intent.color) }
            }

            is ProjectDetailIntent.Domain.UpdateIcon -> {
                mutate { copy(icon = intent.icon) }
            }

            is ProjectDetailIntent.Domain.UpdateParent -> {
                mutate { copy(parentId = intent.parentId) }
            }

            is ProjectDetailIntent.Domain.UpdateDueDate -> {
                mutate { copy(dueDate = intent.dueDate) }
            }

            // ── Lifecycle ──────────────────────────────────────────────────
            is ProjectDetailIntent.Domain.ToggleArchive -> {
                mutate { copy(isDeleted = !isDeleted) }
            }

            is ProjectDetailIntent.Domain.Delete ->
                vmScope.launch {
                    deleteProject(projectId)
                        .onSuccess { emit(ProjectDetailUiEvent.NavigateBack) }
                        .onFailure { e ->
                            emit(
                                ProjectDetailUiEvent.ShowError(
                                    e.toMessage("Delete failed"),
                                ),
                            )
                        }
                }

            // ── Tasks ──────────────────────────────────────────────────────
            is ProjectDetailIntent.Domain.CreateTask -> {
                val trimmed = intent.title.trim()
                if (trimmed.isEmpty()) return
                vmScope.launch {
                    createTaskUseCase(
                        CreateTaskInput(
                            title = trimmed,
                            projectId = projectId,
                            kind = TaskKind.Task,
                        ),
                    ).onFailure { e ->
                        emit(
                            ProjectDetailUiEvent.ShowError(
                                e.toMessage("Create task failed"),
                            ),
                        )
                    }
                }
            }

            is ProjectDetailIntent.Domain.MoveTaskToProject ->
                vmScope.launch {
                    updateTask.invoke(intent.taskId) { it.copy(projectId = projectId) }
                        .onFailure { e ->
                            emit(
                                ProjectDetailUiEvent.ShowError(
                                    e.toMessage("Move task failed"),
                                ),
                            )
                        }
                }

            is ProjectDetailIntent.Domain.ToggleTaskPin ->
                vmScope.fireAndForget(
                    errorLabel = "Pin failed",
                    onError = { e ->
                        vmScope.launch { emit(ProjectDetailUiEvent.ShowError(e.toMessage("Pin failed"))) }
                    },
                ) {
                    taskRepo.togglePinned(intent.taskId)
                }

            is ProjectDetailIntent.Domain.DeleteTask ->
                vmScope.launch {
                    taskRepo.softDelete(intent.taskId)
                        .onFailure { e ->
                            emit(
                                ProjectDetailUiEvent.ShowError(
                                    e.toMessage("Delete task failed"),
                                ),
                            )
                        }
                }
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Applies a mutation to the current project and persists via [updateProject].
     * Reads from [_latestProject] inside the fireAndForget block to avoid TOCTOU.
     */
    private fun mutate(transform: Project.() -> Project) {
        vmScope.fireAndForget(
            errorLabel = "Update project failed",
            onError = { e -> vmScope.launch { emit(ProjectDetailUiEvent.ShowError(e.toMessage())) } },
        ) {
            updateProject(projectId, transform).also {
                if (it.isSuccess) _lastEditedAt.value = clock.now()
            }
        }
    }
}
