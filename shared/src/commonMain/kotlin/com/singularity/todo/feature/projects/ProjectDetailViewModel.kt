package com.singularity.todo.feature.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.tasks.Task
import com.singularity.todo.feature.tasks.TaskFilter
import com.singularity.todo.feature.tasks.TaskId
import com.singularity.todo.feature.tasks.TaskKind
import com.singularity.todo.feature.tasks.TaskPriority
import com.singularity.todo.feature.tasks.TaskRepository
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    private val currentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
) : ViewModel() {

    // ─── UI State ───────────────────────────────────────────────────────────────

    private val _hideCompleted = MutableStateFlow(false)
    val hideCompleted: StateFlow<Boolean> = _hideCompleted

    /** Emits null on start (loading placeholder), then the project flow. */
    private val projectFlow: StateFlow<Project?> = projectRepo.watchProject(projectId)
        .onStart { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProjectDetailUiState.Loading)

    // ─── Silent debounce for inline edits ───────────────────────────────────────

    private val _lastEditedAt = MutableStateFlow<Instant?>(null)
    val lastEditedAt: StateFlow<Instant?> = _lastEditedAt

    private var debounceNameJob: Job? = null
    private var debounceDescJob: Job? = null

    // ─── One-shot events ─────────────────────────────────────────────────────────

    private val _events = MutableSharedFlow<ProjectDetailUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ProjectDetailUiEvent> = _events.asSharedFlow()

    // ─── Intents ─────────────────────────────────────────────────────────────────

    fun toggleHideCompleted() {
        _hideCompleted.value = !_hideCompleted.value
    }

    /** Silently debounced — updates [_lastEditedAt] but does NOT emit Saved. */
    fun updateName(name: String) {
        debounceNameJob?.cancel()
        debounceNameJob = viewModelScope.launch {
            delay(300)
            updateProject(projectId) { it.copy(name = name) }
            _lastEditedAt.value = clock.now()
        }
    }

    /** Silently debounced — updates [_lastEditedAt] but does NOT emit Saved. */
    fun updateDescription(description: String?) {
        debounceDescJob?.cancel()
        debounceDescJob = viewModelScope.launch {
            delay(300)
            updateProject(projectId) { it.copy(description = description) }
            _lastEditedAt.value = clock.now()
        }
    }

    fun updateColor(color: Int) = viewModelScope.launch {
        updateProject(projectId) { it.copy(color = color) }
        _events.emit(ProjectDetailUiEvent.Saved)
    }

    fun updateIcon(icon: String?) = viewModelScope.launch {
        updateProject(projectId) { it.copy(icon = icon) }
        _events.emit(ProjectDetailUiEvent.Saved)
    }

    fun updateParent(parentId: ProjectId?) = viewModelScope.launch {
        updateProject(projectId) { it.copy(parentId = parentId) }
        _events.emit(ProjectDetailUiEvent.Saved)
    }

    fun updateDueDate(dueDate: kotlinx.datetime.LocalDate?) = viewModelScope.launch {
        updateProject(projectId) { it.copy(dueDate = dueDate) }
        _events.emit(ProjectDetailUiEvent.Saved)
    }

    fun toggleArchive() = viewModelScope.launch {
        val current = (state.value as? ProjectDetailUiState.Content)?.ui?.project ?: return@launch
        updateProject(projectId) { it.copy(isDeleted = !current.isDeleted) }
        _events.emit(ProjectDetailUiEvent.Saved)
    }

    fun delete() = viewModelScope.launch {
        deleteProject(projectId, currentUser.scopedUserId.value.value)
            .onSuccess {
                _events.emit(ProjectDetailUiEvent.NavigateBack)
            }
            .onFailure { error ->
                _events.emit(ProjectDetailUiEvent.ShowError(
                    (error as? AppError)?.message ?: error.message ?: "Delete failed"
                ))
            }
    }

    fun createTask(title: String) = viewModelScope.launch {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return@launch
        val now = clock.now()
        runCatching {
            taskRepo.create(
                Task(
                    id = TaskId.generate(),
                    title = trimmed,
                    kind = TaskKind.Task,
                    priority = TaskPriority.None,
                    projectId = projectId,
                    createdAt = now,
                    updatedAt = now,
                    userId = UserId(currentUser.scopedUserId.value.value),
                )
            )
        }.onFailure { error ->
            _events.emit(ProjectDetailUiEvent.ShowError(
                (error as? AppError)?.message ?: error.message ?: "Create task failed"
            ))
        }
    }

    fun duplicate() = viewModelScope.launch {
        val current = (state.value as? ProjectDetailUiState.Content)?.ui?.project ?: return@launch
        // Triggers Saved event for explicit duplicate action
        _events.emit(ProjectDetailUiEvent.Saved)
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
    data object Saved : ProjectDetailUiEvent            // explicit action
    data object NavigateBack : ProjectDetailUiEvent      // after successful delete
    data object NavigateToTasks : ProjectDetailUiEvent   // "See all N tasks"
    data object AddTask : ProjectDetailUiEvent          // quick-add submitted
    data class ShowError(val message: String) : ProjectDetailUiEvent
}
