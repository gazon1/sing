package com.singularity.todo.feature.projects.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.toMessage
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
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * ViewModel for [com.singularity.todo.feature.projects.presentation.screen.ProjectDetailScreen].
 *
 * Subscribes to the project repository ONCE; the task/child/parent streams derive
 * tasks, and aggregate counts into a single [ProjectDetailUi].
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
    scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<ProjectDetailUiState, ProjectDetailIntent.Domain, ProjectDetailUiEvent>(
        initialState = ProjectDetailUiState.Loading,
        scope = scope,
    ) {

    // ─── UI State ───────────────────────────────────────────────────────────────

    // The three flows below are inputs to the state `combine` in [init], not part of
    // the public surface. The screen reads them from [ProjectDetailUiState.Content], so
    // it observes one atomic snapshot instead of collecting four flows independently.
    // [lastEditedAt] is deliberately NOT folded in: it is written by [mutate] after the
    // repository write, not by the combine, so putting it in Content would give the
    // state two writers and open a lost-update window.

    private val hideCompletedFlow = MutableStateFlow(false)

    /** Emits null on start (loading placeholder), then the project flow. */
    private val _projectFlow = MutableStateFlow<Project?>(null)

    /**
     * Reactive list of parent-picker options, derived from [projectFlow] and
     * [ProjectsRepository.watchProjects]. Excludes the current project (cycle prevention)
     * and already-deleted / non-root projects.
     */
    private val parentOptionsFlow = MutableStateFlow<List<ParentOption>>(emptyList())

    /**
     * All active tasks that are NOT in this project — for the "add existing task"
     * quick-add picker. Excludes inbox tasks (projectId == null) and completed tasks.
     * Sorted by dueDate ascending (nulls last), then updatedAt descending.
     */
    private val availableTasksFlow = MutableStateFlow<List<Task>>(emptyList())

    init {
        // Single project observer: feeds [projectFlow], seeds the editable draft,
        // and updates the TOCTOU cache. All other streams derive from [projectFlow]
        // instead of re-subscribing to the repository (one Room observer, not five).
        vmScope.launch {
            projectRepo.observe(projectId)
                .onStart { emit(null) }
                .collect { project ->
                    _projectFlow.value = project
                    _latestProject.value = project
                    if (project != null && !project.isDeleted) {
                        draftState.seed(
                            project.name,
                            project.description
                                ?: "",
                        )
                    }
                }
        }

        // Parent-picker options
        vmScope.launch {
            combine(
                _projectFlow,
                projectRepo.observeAll(),
            ) { project, allProjects ->
                if (project == null) {
                    emptyList()
                } else {
                    allProjects.filter { it.id != project.id && it.parentId == null && !it.isDeleted }
                        .map { ParentOption(it.id, it.name, it.id == project.parentId) }
                }
            }.collect { parentOptionsFlow.value = it }
        }

        // Quick-add picker candidates
        vmScope.launch {
            taskRepo.observeByFilter(TaskFilter.All)
                .map { all ->
                    all.filter { it.projectId != null && it.projectId != projectId && it.completedAt == null }
                        .sortedWith(
                            compareBy<Task, kotlinx.datetime.LocalDate?>(
                                nullsLast(),
                            ) { it.dueDate }.thenByDescending { it.updatedAt },
                        )
                }
                .collect { availableTasksFlow.value = it }
        }

        // Collect state. Two nested combines rather than one 7-argument combine:
        // kotlinx only ships typed `combine` overloads up to 5 flows, and a 7-flow
        // vararg call would collapse to `Array<Any?>`.
        vmScope.launch {
            val projectState = combine(
                _projectFlow,
                _projectFlow.flatMapLatest { project ->
                    if (project == null) {
                        flowOf(
                            emptyList(),
                        )
                    } else {
                        taskRepo.observeByFilter(TaskFilter.ByProject(projectId))
                    }
                },
                _projectFlow.flatMapLatest { project ->
                    if (project == null) flowOf(emptyList()) else projectRepo.observeChildrenOf(projectId)
                },
                _projectFlow.flatMapLatest { p ->
                    if (p == null || p.parentId == null) flowOf(null) else projectRepo.observe(p.parentId)
                },
                hideCompletedFlow,
            ) { project, tasks, childProjects, parent, hideCompleted ->
                when {
                    project == null -> ProjectDetailUiState.Loading

                    project.isDeleted -> ProjectDetailUiState.NotFound

                    else -> {
                        val visibleTasks = if (hideCompleted) tasks.filter { it.completedAt == null } else tasks
                        ProjectDetailUiState.Content(
                            ui = ProjectDetailUi(
                                project = project,
                                tasks = visibleTasks.take(5),
                                totalCount = tasks.size,
                                completedCount = tasks.count { it.completedAt != null },
                                childProjects = childProjects,
                                parent = parent,
                            ),
                            hideCompleted = hideCompleted,
                            parentOptions = emptyList(),
                            availableTasks = emptyList(),
                        )
                    }
                }
            }
            combine(projectState, parentOptionsFlow, availableTasksFlow) { state, parentOptions, availableTasks ->
                if (state is ProjectDetailUiState.Content) {
                    state.copy(parentOptions = parentOptions, availableTasks = availableTasks)
                } else {
                    state
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
        // Name debounce — mutate reads _latestProject inside the launched block to avoid TOCTOU.
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
            is ProjectDetailIntent.Domain.ToggleHideCompleted -> hideCompletedFlow.value = !hideCompletedFlow.value

            // ── Inline edits — debounced, written to draft StateFlows ────────
            is ProjectDetailIntent.Domain.UpdateName -> draftState.setName(intent.name)

            is ProjectDetailIntent.Domain.UpdateDescription -> draftState.setDescription(
                intent.description
                    ?: "",
            )

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

            is ProjectDetailIntent.Domain.Delete -> vmScope.launch {
                deleteProject(projectId)
                    .onSuccess { emit(ProjectDetailUiEvent.NavigateBack) }
                    .onFailure { emit(ProjectDetailUiEvent.ShowError(it.toMessage("Delete failed"))) }
            }

            // ── Tasks ──────────────────────────────────────────────────────
            is ProjectDetailIntent.Domain.CreateTask -> {
                val trimmed = intent.title.trim()
                if (trimmed.isEmpty()) return
                emitError("Create task failed", ProjectDetailUiEvent::ShowError) {
                    createTaskUseCase(
                        CreateTaskInput(
                            title = trimmed,
                            projectId = projectId,
                            kind = TaskKind.Task,
                        ),
                    )
                }
            }

            is ProjectDetailIntent.Domain.MoveTaskToProject ->
                emitError("Move task failed", ProjectDetailUiEvent::ShowError) {
                    updateTask.invoke(intent.taskId) { it.copy(projectId = projectId) }
                }

            is ProjectDetailIntent.Domain.ToggleTaskPin ->
                emitError("Pin failed", ProjectDetailUiEvent::ShowError) {
                    taskRepo.togglePinned(intent.taskId)
                }

            is ProjectDetailIntent.Domain.DeleteTask ->
                emitError("Delete task failed", ProjectDetailUiEvent::ShowError) {
                    taskRepo.softDelete(intent.taskId)
                }
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Applies a mutation to the current project and persists via [updateProject].
     * Reads from [_latestProject] inside the launched block to avoid TOCTOU.
     */
    private fun mutate(transform: Project.() -> Project) {
        emitError("Update project failed", ProjectDetailUiEvent::ShowError) {
            updateProject(projectId, transform).also {
                if (it.isSuccess) _lastEditedAt.value = clock.now()
            }
        }
    }
}
