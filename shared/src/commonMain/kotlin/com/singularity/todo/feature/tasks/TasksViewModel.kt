package com.singularity.todo.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.feature.ai.use_cases.DecomposeTaskUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateChecklistUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateDescriptionUseCase
import com.singularity.todo.feature.ai.use_cases.PickTimeUseCase
import com.singularity.todo.feature.ai.use_cases.RefineTaskUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface TasksUiState {
    data object Loading : TasksUiState
    data class Empty(val filter: TaskFilter) : TasksUiState
    data class Content(val filter: TaskFilter, val tasks: List<Task>) : TasksUiState
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

@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModel(
    private val taskRepo: TaskRepository,
    private val createTask: CreateTaskUseCase,
    private val updateTask: UpdateTaskUseCase,
    private val settingsRepository: SettingsRepository,
    private val refineTask: RefineTaskUseCase,
    private val generateDescription: GenerateDescriptionUseCase,
    private val generateChecklist: GenerateChecklistUseCase,
    private val decomposeTask: DecomposeTaskUseCase,
    private val pickTime: PickTimeUseCase,
) : ViewModel() {

    private val _filter = MutableStateFlow<TaskFilter>(TaskFilter.Today)
    val filter: StateFlow<TaskFilter> = _filter.asStateFlow()

    private val userId: Flow<UserId> = settingsRepository.userId.map { UserId.fromString(it) }

    private val _aiResult = MutableSharedFlow<AiActionResult>()
    val aiResult = _aiResult.asSharedFlow()

    val state: StateFlow<TasksUiState> = combine(_filter, userId) { f, uid -> f to uid }
        .flatMapLatest { (filter, uid) -> taskRepo.watchTasks(uid, filter) }
        .map<List<Task>, TasksUiState> { tasks ->
            if (tasks.isEmpty()) TasksUiState.Empty(_filter.value)
            else TasksUiState.Content(_filter.value, tasks)
        }
        .catch { emit(TasksUiState.Error(it.message ?: "Error")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TasksUiState.Loading)

    fun setFilter(filter: TaskFilter) {
        _filter.value = filter
    }

    fun delete(id: TaskId) = viewModelScope.launch {
        taskRepo.softDelete(id)
    }

    fun toggle(id: TaskId) = viewModelScope.launch {
        taskRepo.toggleComplete(id)
    }

    fun refineTaskTitle(task: Task) = viewModelScope.launch {
        refineTask(task.title, task.description)
            .onSuccess { _aiResult.emit(AiActionResult.RefineTitle(it)) }
            .onFailure { _aiResult.emit(AiActionResult.Error(it.message ?: "Failed")) }
    }

    fun generateTaskDescription(task: Task) = viewModelScope.launch {
        generateDescription(task.title)
            .onSuccess { _aiResult.emit(AiActionResult.GenerateDescription(it)) }
            .onFailure { _aiResult.emit(AiActionResult.Error(it.message ?: "Failed")) }
    }

    fun generateChecklist(task: Task) = viewModelScope.launch {
        generateChecklist(task.title, task.description)
            .onSuccess { _aiResult.emit(AiActionResult.GenerateChecklist(it)) }
            .onFailure { _aiResult.emit(AiActionResult.Error(it.message ?: "Failed")) }
    }

    fun decomposeTask(task: Task) = viewModelScope.launch {
        decomposeTask(task.title, task.description)
            .onSuccess { _aiResult.emit(AiActionResult.DecomposeTask(it)) }
            .onFailure { _aiResult.emit(AiActionResult.Error(it.message ?: "Failed")) }
    }

    fun suggestTime(task: Task) = viewModelScope.launch {
        pickTime(task.title, task.description)
            .onSuccess { _aiResult.emit(AiActionResult.PickTime(it)) }
            .onFailure { _aiResult.emit(AiActionResult.Error(it.message ?: "Failed")) }
    }
}
