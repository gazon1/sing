package com.singularity.todo.feature.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.tasks.Task
import com.singularity.todo.feature.tasks.TaskFilter
import com.singularity.todo.feature.tasks.TaskId
import com.singularity.todo.feature.tasks.CreateTaskInput
import com.singularity.todo.feature.tasks.CreateTaskUseCase
import com.singularity.todo.feature.tasks.TaskKind
import com.singularity.todo.feature.tasks.TaskPriority
import com.singularity.todo.feature.tasks.TaskRepository
import com.singularity.todo.feature.tasks.UpdateTaskUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * ViewModel for [ProjectDetailScreen].
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
    private val currentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
    private val scopeOverride: CoroutineScope? = null,
    sharingStarted: () -> SharingStarted = { SharingStarted.WhileSubscribed(5000) },
) : ViewModel() {
    private val scope get() = scopeOverride ?: viewModelScope

    // ─── UI State ───────────────────────────────────────────────────────────────

    private val _hideCompleted = MutableStateFlow(false)
    val hideCompleted: StateFlow<Boolean> = _hideCompleted

    /** Emits null on start (loading placeholder), then the project flow. */
    private val projectFlow: StateFlow<Project?> = projectRepo.watchProject(projectId)
        .onStart { emit(null) }
        .stateIn(scope, sharingStarted(), null)

    /**
     * Reactive list of parent-picker options, derived from [projectFlow] and
     * [ProjectsRepository.watchProjects]. Excludes the current project (cycle prevention)
     * and already-deleted / non-root projects.
     */
    val parentOptionsFlow: StateFlow<List<ParentOption>> = combine(
        projectFlow,
        projectRepo.watchProjects(currentUser.scopedUserId.value.value),
    ) { project, allProjects ->
        if (project == null) emptyList()
        else allProjects
            .filter { it.id != project.id && it.parentId == null && !it.isDeleted }
            .map { ParentOption(it.id, it.name, it.id == project.parentId) }
    }.stateIn(scope, sharingStarted(), emptyList())

    /**
     * All active tasks that are NOT in this project — for the "add existing task"
     * quick-add picker. Excludes inbox tasks (projectId == null) and completed tasks.
     * Sorted by dueDate ascending (nulls last), then updatedAt descending.
     */
    val availableTasksFlow: StateFlow<List<Task>> =
        taskRepo.watchTasks(currentUser.scopedUserId.value, TaskFilter.All)
            .map { all ->
                all
                    .filter { it.projectId != null && it.projectId != projectId && it.completedAt == null }
                    .sortedWith(
                        compareBy<Task, kotlinx.datetime.LocalDate?>(nullsLast()) { it.dueDate }
                            .thenByDescending { it.updatedAt }
                    )
            }
            .stateIn(scope, sharingStarted(), emptyList())

    val state: StateFlow<ProjectDetailUiState> = combine(
        projectFlow,
        projectFlow.flatMapLatest { project ->
            if (project == null) flowOf(emptyList())
            else taskRepo.watchTasks(
                currentUser.scopedUserId.value,
                TaskFilter.ByProject(projectId)
            )
        },
        projectFlow.flatMapLatest { project ->
            if (project == null) flowOf(emptyList())
            else projectRepo.watchByParent(projectId)
        },
        projectFlow.flatMapLatest { p ->
            if (p == null || p.parentId == null) flowOf(null)
            else projectRepo.watchProject(p.parentId)
        },
        _hideCompleted,
    ) { project, tasks, childProjects, parent, hideCompleted ->
        _latestProject.value = project
        when {
            project == null -> ProjectDetailUiState.Loading
            project.isDeleted -> ProjectDetailUiState.NotFound
            else -> {
                val visibleTasks = if (hideCompleted) tasks.filter { it.completedAt == null } else tasks
                ProjectDetailUiState.Content(
                    ProjectDetailUi(
                        project = project,
                        tasks = visibleTasks.take(5),
                        totalCount = tasks.size,
                        completedCount = tasks.count { it.completedAt != null },
                        childProjects = childProjects,
                        parent = parent,
                    )
                )
            }
        }
    }.stateIn(scope, sharingStarted(), ProjectDetailUiState.Loading)

    // ─── Silent debounce for inline edits ───────────────────────────────────────

    private val _lastEditedAt = MutableStateFlow<Instant?>(null)
    val lastEditedAt: StateFlow<Instant?> = _lastEditedAt

    /** Draft flows — written by onIntent, collected and debounced in init{}. */
    private val nameDraft = MutableStateFlow<String?>(null)
    private val descriptionDraft = MutableStateFlow<String?>(null)

    init {
        // Name debounce — reads _latestProject to avoid TOCTOU.
        viewModelScope.launch {
            nameDraft
                .debounce(300.milliseconds)
                .filterNotNull()
                .distinctUntilChanged()
                .collect { name ->
                    val current = _latestProject.value ?: return@collect
                    mutate(current) { copy(name = name) }
                }
        }
        // Description debounce — same pattern.
        viewModelScope.launch {
            descriptionDraft
                .debounce(300.milliseconds)
                .filterNotNull()
                .distinctUntilChanged()
                .collect { desc ->
                    val current = _latestProject.value ?: return@collect
                    mutate(current) { copy(description = desc) }
                }
        }
    }

    // ─── Cached latest project — TOCTOU guard ────────────────────────────────

    private val _latestProject = MutableStateFlow<Project?>(null)

    // ─── One-shot events ─────────────────────────────────────────────────────────

    private val _events = MutableSharedFlow<ProjectDetailUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ProjectDetailUiEvent> = _events.asSharedFlow()

    // ─── Intent dispatcher ─────────────────────────────────────────────────────

    /**
     * Единственный публичный метод для всех доменных операций.
     *
     * Routing-интенты ([ProjectDetailIntent.Routing]) обрабатываются экраном
     * и сюда не попадают.
     */
    fun onIntent(intent: ProjectDetailIntent.Domain) {
        when (intent) {
            // ── Visibility ──────────────────────────────────────────────────
            is ProjectDetailIntent.Domain.ToggleHideCompleted ->
                _hideCompleted.value = !_hideCompleted.value

            // ── Inline edits — debounced, written to draft StateFlows ────────
            is ProjectDetailIntent.Domain.UpdateName ->
                nameDraft.value = intent.name
            is ProjectDetailIntent.Domain.UpdateDescription ->
                descriptionDraft.value = intent.description

            // ── Pickers ─────────────────────────────────────────────────────
            is ProjectDetailIntent.Domain.UpdateColor -> {
                val current = _latestProject.value ?: return
                mutate(current) { copy(color = intent.color) }
            }
            is ProjectDetailIntent.Domain.UpdateIcon -> {
                val current = _latestProject.value ?: return
                mutate(current) { copy(icon = intent.icon) }
            }
            is ProjectDetailIntent.Domain.UpdateParent -> {
                val current = _latestProject.value ?: return
                mutate(current) { copy(parentId = intent.parentId) }
            }
            is ProjectDetailIntent.Domain.UpdateDueDate -> {
                val current = _latestProject.value ?: return
                mutate(current) { copy(dueDate = intent.dueDate) }
            }

            // ── Lifecycle ──────────────────────────────────────────────────
            is ProjectDetailIntent.Domain.ToggleArchive -> {
                val current = _latestProject.value ?: return
                mutate(current) { copy(isDeleted = !isDeleted) }
            }
            is ProjectDetailIntent.Domain.Delete ->
                viewModelScope.launch {
                    deleteProject(projectId, currentUser.scopedUserId.value.value)
                        .onSuccess { _events.emit(ProjectDetailUiEvent.NavigateBack) }
                        .onFailure { error ->
                            _events.emit(ProjectDetailUiEvent.ShowError(
                                (error as? AppError)?.message ?: error.message ?: "Delete failed"
                            ))
                        }
                }

            // ── Tasks ──────────────────────────────────────────────────────
            is ProjectDetailIntent.Domain.CreateTask -> {
                val trimmed = intent.title.trim()
                if (trimmed.isEmpty()) return
                viewModelScope.launch {
                    createTaskUseCase(
                        CreateTaskInput(
                            title = trimmed,
                            userId = currentUser.scopedUserId.value,
                            projectId = projectId,
                            kind = TaskKind.Task,
                        )
                    ).onFailure { error ->
                        _events.emit(ProjectDetailUiEvent.ShowError(
                            (error as? AppError)?.message ?: error.message ?: "Create task failed"
                        ))
                    }
                }
            }
            is ProjectDetailIntent.Domain.MoveTaskToProject ->
                viewModelScope.launch {
                    updateTask.invoke(intent.taskId) { it.copy(projectId = projectId) }
                        .onFailure { error ->
                            _events.emit(ProjectDetailUiEvent.ShowError(
                                (error as? AppError)?.message ?: error.message ?: "Move task failed"
                            ))
                        }
                }
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Applies a mutation to [current] via [transform] and persists via [updateProject].
     * Uses [_latestProject] as the source of truth to avoid TOCTOU.
     */
    private fun mutate(
        current: Project,
        transform: Project.() -> Project,
    ) {
        viewModelScope.launch {
            updateProject(projectId, transform)
                .onSuccess { _lastEditedAt.value = clock.now() }
                .onFailure { /* silent — UI already reflects the draft */ }
        }
    }
}

// ─── UI State ─────────────────────────────────────────────────────────────────

sealed interface ProjectDetailUiState {
    data object Loading : ProjectDetailUiState
    data object NotFound : ProjectDetailUiState
    data class Content(val ui: ProjectDetailUi) : ProjectDetailUiState
}

// ─── Events ───────────────────────────────────────────────────────────────────

sealed interface ProjectDetailUiEvent {
    data object NavigateBack : ProjectDetailUiEvent  // after successful delete
    data class ShowError(val message: String) : ProjectDetailUiEvent
}
