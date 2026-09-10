package com.singularity.todo.feature.tasks.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.feature.ai.use_cases.DecomposeTaskUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateChecklistUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateDescriptionUseCase
import com.singularity.todo.feature.ai.use_cases.PickTimeUseCase
import com.singularity.todo.feature.ai.use_cases.RefineTaskUseCase
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.AiActionResult
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskAiAction
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskGroup
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TasksUiEvent
import com.singularity.todo.feature.tasks.domain.model.TasksUiState
import com.singularity.todo.feature.tasks.domain.model.formatAiResult
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskUseCase
import com.singularity.todo.feature.tasks.domain.usecase.TaskMutationsUseCase
import com.singularity.todo.feature.tasks.domain.usecase.UpdateTaskUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModel(
    private val taskRepo: TaskRepository,
    private val createTask: CreateTaskUseCase,
    private val updateTask: UpdateTaskUseCase,
    currentUser: ProfileAwareCurrentUser,
    private val mutations: TaskMutationsUseCase,
    private val refineTask: RefineTaskUseCase? = null,
    private val generateDescription: GenerateDescriptionUseCase? = null,
    private val generateChecklist: GenerateChecklistUseCase? = null,
    private val decomposeTask: DecomposeTaskUseCase? = null,
    private val pickTime: PickTimeUseCase? = null,
    sharingStarted: () -> SharingStarted = { SharingStarted.WhileSubscribed(5000) },
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    private val _filter = MutableStateFlow<TaskFilter>(TaskFilter.All)
    val filter: StateFlow<TaskFilter> = _filter.asStateFlow()

    private val _aiResult = MutableSharedFlow<AiActionResult>()
    val aiResult = _aiResult.asSharedFlow()

    private val _events = MutableSharedFlow<TasksUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<TasksUiEvent> = _events.asSharedFlow()

    private val _selectedIds = MutableStateFlow<Set<TaskId>>(emptySet())
    val selectedIds: StateFlow<Set<TaskId>> = _selectedIds.asStateFlow()

    private val _expandedParentIds = MutableStateFlow<Set<TaskId>>(emptySet())

    val state: StateFlow<TasksUiState> = combine(
        combine(_filter, currentUser.scopedUserId) { f, uid -> f to uid }
            .flatMapLatest { (filter, uid) -> taskRepo.watchTasks(uid, filter) },
        _expandedParentIds,
        _selectedIds,
    ) { tasks, expandedIds, selectedIds ->
        val groups = deriveTaskGroups(tasks, expandedIds)
        if (groups.isEmpty()) TasksUiState.Empty(_filter.value)
        else TasksUiState.Content(_filter.value, groups, selectedIds)
    }
        .catch { emit(TasksUiState.Error(it.message ?: "Error")) }
        .stateIn(scope, sharingStarted(), TasksUiState.Loading)

    private fun deriveTaskGroups(tasks: List<Task>, expandedIds: Set<TaskId>): List<TaskGroup> {
        val topLevel = tasks.filter { it.parentTaskId == null }
        return topLevel.map { parent ->
            val children = tasks.filter { it.parentTaskId == parent.id }
            TaskGroup.TopLevel(parent, children, isExpanded = parent.id in expandedIds)
        }
    }

    fun setFilter(filter: TaskFilter) {
        _filter.value = filter
    }

    fun delete(id: TaskId) = scope.launch {
        taskRepo.softDelete(id)
    }

    fun toggle(id: TaskId) = scope.launch {
        taskRepo.toggleComplete(id)
    }

    fun togglePin(id: TaskId) = scope.launch {
        taskRepo.togglePinned(id)
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

    fun bulkCompleteSelected() = scope.launch {
        mutations.bulkComplete(_selectedIds.value.toList())
        exitSelectionMode()
    }

    fun bulkDeleteSelected() = scope.launch {
        mutations.bulkDelete(_selectedIds.value.toList())
        exitSelectionMode()
    }

    fun runAiAction(task: Task, action: TaskAiAction) = scope.launch {
        val result: AiActionResult = when (action) {
            TaskAiAction.RefineTitle -> refineTask?.invoke(task.title, task.description)?.toResult(AiActionResult::RefineTitle)
                ?: AiActionResult.Error("AI not available")
            TaskAiAction.GenerateDescription -> generateDescription?.invoke(task.title)?.toResult(AiActionResult::GenerateDescription)
                ?: AiActionResult.Error("AI not available")
            TaskAiAction.GenerateChecklist -> generateChecklist?.invoke(task.title, task.description)?.toResult(AiActionResult::GenerateChecklist)
                ?: AiActionResult.Error("AI not available")
            TaskAiAction.Decompose -> decomposeTask?.invoke(task.title, task.description)?.toResult(AiActionResult::DecomposeTask)
                ?: AiActionResult.Error("AI not available")
            TaskAiAction.SuggestTime -> pickTime?.invoke(task.title, task.description)?.toResult(AiActionResult::PickTime)
                ?: AiActionResult.Error("AI not available")
        }
        _aiResult.emit(result)
        _events.emit(TasksUiEvent.AiResult(formatAiResult(result)))
    }

    private fun <T> Result<T>.toResult(ok: (T) -> AiActionResult): AiActionResult =
        fold(onSuccess = ok, onFailure = { AiActionResult.Error(it.message ?: "Failed") })
}
