package com.singularity.todo.feature.tasks.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.coroutines.fireAndForget
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.ai.use_cases.DecomposeTaskUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateChecklistUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateDescriptionUseCase
import com.singularity.todo.feature.ai.use_cases.PickTimeUseCase
import com.singularity.todo.feature.ai.use_cases.RefineTaskUseCase
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tasks.domain.logic.TaskComputed
import com.singularity.todo.feature.tasks.domain.model.AiActionResult
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskAiAction
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import com.singularity.todo.feature.tasks.domain.model.TasksUiEvent
import com.singularity.todo.feature.tasks.domain.model.TasksUiState
import com.singularity.todo.feature.tasks.domain.model.formatAiResult
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskUseCase
import com.singularity.todo.feature.tasks.domain.usecase.TaskMutationsUseCase
import com.singularity.todo.feature.tasks.domain.usecase.UpdateTaskUseCase
import com.singularity.todo.feature.tasks.presentation.model.TaskUi
import com.singularity.todo.feature.tasks.presentation.model.toTaskUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/**
 * Tasks list screen ViewModel.
 *
 * Owns: filtered task list via [TasksUiState], task mutations, AI actions on tasks.
 * Triggers: [TaskFilter] changes, task mutations (create/complete/pin/delete), AI actions
 *   (refine, decompose, generate description/checklist, pick time, project review).
 * One-shot events: [TasksUiEvent.NavigateToDetail], [TasksUiEvent.NavigateToCreate],
 *   [TasksUiEvent.NavigateToProject], [TasksUiEvent.ShowAiResult], [TasksUiEvent.ShowError].
 *
 * @see TasksUiState
 * @see TasksUiEvent
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModel(
    private val taskRepo: TaskRepository,
    private val createTask: CreateTaskUseCase,
    private val updateTask: UpdateTaskUseCase,
    currentUser: ProfileAwareCurrentUser,
    private val mutations: TaskMutationsUseCase,
    private val projectRepo: ProjectsRepository,
    private val clock: Clock,
    private val refineTask: RefineTaskUseCase? = null,
    private val generateDescription: GenerateDescriptionUseCase? = null,
    private val generateChecklist: GenerateChecklistUseCase? = null,
    private val decomposeTask: DecomposeTaskUseCase? = null,
    private val pickTime: PickTimeUseCase? = null,
    sharingStarted: () -> SharingStarted = { SharingStarted.WhileSubscribed(5000) },
    private val scope: CoroutineScope,
) : ViewModel() {

    /** Production constructor — Koin uses this. */
    constructor(
        taskRepo: TaskRepository,
        createTask: CreateTaskUseCase,
        updateTask: UpdateTaskUseCase,
        currentUser: ProfileAwareCurrentUser,
        mutations: TaskMutationsUseCase,
        projectRepo: ProjectsRepository,
        clock: Clock,
        refineTask: RefineTaskUseCase? = null,
        generateDescription: GenerateDescriptionUseCase? = null,
        generateChecklist: GenerateChecklistUseCase? = null,
        decomposeTask: DecomposeTaskUseCase? = null,
        pickTime: PickTimeUseCase? = null,
    ) : this(
        taskRepo = taskRepo,
        createTask = createTask,
        updateTask = updateTask,
        currentUser = currentUser,
        mutations = mutations,
        projectRepo = projectRepo,
        clock = clock,
        refineTask = refineTask,
        generateDescription = generateDescription,
        generateChecklist = generateChecklist,
        decomposeTask = decomposeTask,
        pickTime = pickTime,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        sharingStarted = { SharingStarted.WhileSubscribed(5000) },
    )

    /** Pre-computed "today" — stable for the lifetime of the ViewModel. */
    private val today: LocalDate = LocalDate.fromEpochDays(
        clock.now().toEpochMilliseconds() / (24 * 60 * 60 * 1000),
    )

    private val _filter = MutableStateFlow<TaskFilter>(TaskFilter.All)
    val filter: StateFlow<TaskFilter> = _filter.asStateFlow()

    /** UI-side status filter (ALL / ACTIVE / COMPLETED) — independent of domain filter. */
    private val _statusFilter = MutableStateFlow(TaskStatus.All)
    val statusFilter: StateFlow<TaskStatus> = _statusFilter.asStateFlow()

    private val _aiResult = MutableSharedFlow<AiActionResult>()
    val aiResult: SharedFlow<AiActionResult> = _aiResult.asSharedFlow()

    private val _events = Channel<TasksUiEvent>(Channel.BUFFERED)
    val events: kotlinx.coroutines.flow.Flow<TasksUiEvent> = _events.receiveAsFlow()

    private val _selectedIds = MutableStateFlow<Set<TaskId>>(emptySet())
    val selectedIds: StateFlow<Set<TaskId>> = _selectedIds.asStateFlow()

    private val _expandedParentIds = MutableStateFlow<Set<TaskId>>(emptySet())

    /** Snapshot of the most recently deleted task for undo. Lives in VM (not SharedFlow) because StateFlow survives recomposition. */
    private val _recentlyDeleted = MutableStateFlow<TaskUi?>(null)
    val recentlyDeleted: StateFlow<TaskUi?> = _recentlyDeleted.asStateFlow()

    // All tasks from repo, updated when filter or user changes
    private val tasksFlow: Flow<List<Task>> = combine(
        _filter,
        currentUser.scopedUserId,
    ) { filter, uid -> filter to uid }
        .flatMapLatest { (filter, uid) -> taskRepo.watchTasks(uid, filter) }

    // Reactive project names — automatically updates when projects change or user switches profile
    private val projectNamesFlow: StateFlow<Map<String, String>> =
        currentUser.scopedUserId
            .flatMapLatest { uid -> projectRepo.watchProjects(uid) }
            .map { list -> list.associate { it.id.value to it.name } }
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyMap())

    @Suppress("UNCHECKED_CAST")
    val state: StateFlow<TasksUiState> = (
        combine(
        tasksFlow,
        projectNamesFlow,
        _filter,
        _statusFilter,
        _selectedIds,
        _expandedParentIds,
    ) { args: Array<*> ->
        @Suppress("UNCHECKED_CAST")
        val tasks = args[0] as List<Task>

        @Suppress("UNCHECKED_CAST")
        val projectNames = args[1] as Map<String, String>

        @Suppress("UNCHECKED_CAST")
        val filter = args[2] as TaskFilter

        @Suppress("UNCHECKED_CAST")
        val statusFilter = args[3] as TaskStatus

        @Suppress("UNCHECKED_CAST")
        val selectedIds = args[4] as Set<TaskId>

        @Suppress("UNCHECKED_CAST")
        val expandedIds = args[5] as Set<TaskId>
        val filtered = when (statusFilter) {
            TaskStatus.All -> tasks
            TaskStatus.Active -> tasks.filter { it.completedAt == null }
            TaskStatus.Completed -> tasks.filter { it.completedAt != null }
        }
        val taskUiList = buildFlatTaskList(filtered, expandedIds, projectNames)
        TasksUiState.Content(filter, taskUiList, selectedIds)
    } as Flow<TasksUiState>
    )
        .catch { emit(TasksUiState.Error(it.message ?: "Error")) }
        .stateIn(scope, sharingStarted(), TasksUiState.Loading)

    private fun buildFlatTaskList(
        tasks: List<Task>,
        expandedIds: Set<TaskId>,
        projectNames: Map<String, String>,
    ): List<TaskUi> {
        val topLevel = tasks.filter { it.parentTaskId == null }
        val result = mutableListOf<TaskUi>()
        for (parent in topLevel) {
            val parentBlocked = TaskComputed.isBlocked(parent, tasks)
            result.add(parent.toTaskUi(today, projectNames, parent.dependsOn, parentBlocked))
            if (parent.id in expandedIds) {
                for (child in tasks) {
                    if (child.parentTaskId == parent.id) {
                        val childBlocked = TaskComputed.isBlocked(child, tasks)
                        result.add(child.toTaskUi(today, projectNames, child.dependsOn, childBlocked))
                    }
                }
            }
        }
        return result
    }

    fun setFilter(filter: TaskFilter) {
        _filter.value = filter
    }

    fun setStatusFilter(filter: TaskStatus) {
        _statusFilter.value = filter
    }

    fun delete(taskUi: TaskUi) {
        _recentlyDeleted.value = taskUi
        scope.fireAndForget(
            errorLabel = "Delete failed",
            onError = { e -> scope.launch { _events.trySend(TasksUiEvent.Error("Delete failed: ${e.message ?: "unknown"}")) } },
        ) {
            taskRepo.softDelete(taskUi.id)
        }
    }

    fun restore() {
        val task = _recentlyDeleted.value ?: return
        scope.fireAndForget(
            errorLabel = "Restore failed",
            onError = { e -> scope.launch { _events.trySend(TasksUiEvent.Error("Restore failed: ${e.message ?: "unknown"}")) } },
        ) {
            taskRepo.restore(task.id).onSuccess { _recentlyDeleted.value = null }
        }
    }

    /** Clears undo state without restoring. Called when snackbar dismisses without action. */
    fun clearUndo() {
        _recentlyDeleted.value = null
    }

    fun toggle(id: TaskId) {
        scope.fireAndForget(
            errorLabel = "Toggle failed",
            onError = { e -> scope.launch { _events.trySend(TasksUiEvent.Error("Toggle failed: ${e.message ?: "unknown"}")) } },
        ) {
            taskRepo.toggleComplete(id)
        }
    }

    fun togglePin(id: TaskId) {
        scope.fireAndForget(
            errorLabel = "Pin failed",
            onError = { e -> scope.launch { _events.trySend(TasksUiEvent.Error("Pin failed: ${e.message ?: "unknown"}")) } },
        ) {
            taskRepo.togglePinned(id)
        }
    }

    fun enterSelectionMode(taskId: TaskId) {
        _selectedIds.value = setOf(taskId)
    }

    fun toggleSelection(id: TaskId) {
        _selectedIds.update { current ->
            if (current.contains(id)) current - id else current + id
        }
    }

    fun exitSelectionMode() {
        _selectedIds.value = emptySet()
    }

    fun toggleExpand(taskId: TaskId) {
        _expandedParentIds.update { current ->
            if (taskId in current) current - taskId else current + taskId
        }
    }

    fun bulkCompleteSelected() {
        val ids = _selectedIds.value.toList()
        scope.fireAndForget(
            errorLabel = "Bulk complete failed",
            onError = { e -> scope.launch { _events.trySend(TasksUiEvent.Error("Bulk complete failed: ${e.message ?: "unknown"}")) } },
        ) {
            mutations.bulkComplete(ids)
        }
        exitSelectionMode()
    }

    fun bulkDeleteSelected() {
        val ids = _selectedIds.value.toList()
        scope.fireAndForget(
            errorLabel = "Bulk delete failed",
            onError = { e -> scope.launch { _events.trySend(TasksUiEvent.Error("Bulk delete failed: ${e.message ?: "unknown"}")) } },
        ) {
            mutations.bulkDelete(ids)
        }
        exitSelectionMode()
    }

    fun runAiAction(task: Task, action: TaskAiAction) = scope.launch {
        val result: AiActionResult = when (action) {
            TaskAiAction.RefineTitle -> refineTask?.invoke(
                task.title,
                task.description,
            )?.toResult(AiActionResult::RefineTitle)
                ?: AiActionResult.Error("AI not available")

            TaskAiAction.GenerateDescription -> generateDescription?.invoke(
                task.title,
            )?.toResult(AiActionResult::GenerateDescription)
                ?: AiActionResult.Error("AI not available")

            TaskAiAction.GenerateChecklist -> generateChecklist?.invoke(
                task.title,
                task.description,
            )?.toResult(AiActionResult::GenerateChecklist)
                ?: AiActionResult.Error("AI not available")

            TaskAiAction.Decompose -> decomposeTask?.invoke(
                task.title,
                task.description,
            )?.toResult(AiActionResult::DecomposeTask)
                ?: AiActionResult.Error("AI not available")

            TaskAiAction.SuggestTime -> pickTime?.invoke(
                task.title,
                task.description,
            )?.toResult(AiActionResult::PickTime)
                ?: AiActionResult.Error("AI not available")
        }
        _aiResult.emit(result)
        _events.trySend(TasksUiEvent.AiResult(formatAiResult(result)))
    }

    private fun <T> Result<T>.toResult(ok: (T) -> AiActionResult): AiActionResult =
        fold(onSuccess = ok, onFailure = { AiActionResult.Error(it.message ?: "Failed") })
}
