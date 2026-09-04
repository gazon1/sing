package com.singularity.todo.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.ui.components.UiEvent
import com.singularity.todo.feature.ai.use_cases.DecomposeTaskUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateChecklistUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateDescriptionUseCase
import com.singularity.todo.feature.ai.use_cases.PickTimeUseCase
import com.singularity.todo.feature.ai.use_cases.RefineTaskUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface TasksUiState {
    data object Loading : TasksUiState
    data class Empty(val filter: TaskFilter) : TasksUiState
    data class Content(
        val filter: TaskFilter,
        val tasks: List<Task>,
        val selectedIds: Set<TaskId> = emptySet(),
    ) : TasksUiState
    data class Error(val message: String) : TasksUiState
}

/** One-shot AI action results shown to user */
sealed interface AiActionResult {
    data class RefineTitle(val newTitle: String) : AiActionResult
    data class GenerateDescription(val description: String) : AiActionResult
    data class GenerateChecklist(val steps: List<String>) : AiActionResult
    data class DecomposeTask(val subTasks: List<String>) : AiActionResult
    data class PickTime(val suggestedTime: String) : AiActionResult
    data class Error(val message: String) : AiActionResult
}

/** Stable, value-classified set of actions a user can trigger from the AI sheet. */
enum class TaskAiAction { RefineTitle, GenerateDescription, GenerateChecklist, Decompose, SuggestTime }

@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModel(
    private val taskRepo: TaskRepository,
    private val createTask: CreateTaskUseCase,
    private val updateTask: UpdateTaskUseCase,
    private val settingsRepository: SettingsRepository,
    // AI use cases are optional — Android doesn't ship with Koog/JVM AI stack,
    // so VMs work with null AI dependencies (AI buttons become no-ops on Android)
    private val refineTask: RefineTaskUseCase? = null,
    private val generateDescription: GenerateDescriptionUseCase? = null,
    private val generateChecklist: GenerateChecklistUseCase? = null,
    private val decomposeTask: DecomposeTaskUseCase? = null,
    private val pickTime: PickTimeUseCase? = null,
    private val sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(5000),
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    private val _filter = MutableStateFlow<TaskFilter>(TaskFilter.Today)
    val filter: StateFlow<TaskFilter> = _filter.asStateFlow()

    private val userId: Flow<UserId> = settingsRepository.userId.map { UserId.fromString(it) }

    private val _aiResult = MutableSharedFlow<AiActionResult>()
    val aiResult = _aiResult.asSharedFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val _selectedIds = MutableStateFlow<Set<TaskId>>(emptySet())
    val selectedIds: StateFlow<Set<TaskId>> = _selectedIds.asStateFlow()

    val state: StateFlow<TasksUiState> = combine(_filter, userId) { f, uid -> f to uid }
        .flatMapLatest { (filter, uid) -> taskRepo.watchTasks(uid, filter) }
        .map { tasks ->
            if (tasks.isEmpty()) TasksUiState.Empty(_filter.value)
            else TasksUiState.Content(_filter.value, tasks, _selectedIds.value)
        }
        .catch { emit(TasksUiState.Error(it.message ?: "Error")) }
        .stateIn(scope, sharingStarted, TasksUiState.Loading)

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

    fun bulkCompleteSelected() = scope.launch {
        _selectedIds.value.forEach { id ->
            taskRepo.toggleComplete(id)
        }
        exitSelectionMode()
    }

    fun bulkDeleteSelected() = scope.launch {
        _selectedIds.value.forEach { id ->
            taskRepo.softDelete(id)
        }
        exitSelectionMode()
    }

    /**
     * Single entry point the UI calls after the user picks an AI action from
     * the bottom sheet. Dispatches to the matching use case (or emits a friendly
     * "AI not available" event when the platform doesn't ship the AI stack).
     */
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
        _events.emit(UiEvent.ShowDialog(title = "AI Result", text = formatAiResult(result)))
    }

    private fun <T> Result<T>.toResult(ok: (T) -> AiActionResult): AiActionResult =
        fold(onSuccess = ok, onFailure = { AiActionResult.Error(it.message ?: "Failed") })
}
