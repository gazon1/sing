package com.singularity.todo.feature.tasks.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskDetailDeps
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiEvent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiState
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class TaskDetailViewModel(
    private val deps: TaskDetailDeps,
    private val taskId: TaskId,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    private val _events = MutableSharedFlow<TaskDetailUiEvent>(replay = 0, extraBufferCapacity = 8)
    val events: SharedFlow<TaskDetailUiEvent> = _events.asSharedFlow()

    private val titleEdits = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 8)
    private val descriptionEdits = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 8)

    /** Visible for tests. TOCTOU: prefer to observe via state. */
    internal val _latestTask = MutableStateFlow<Task?>(null)

    private val _lastEditedAt = MutableStateFlow<kotlin.time.Instant?>(null)
    val lastEditedAt: StateFlow<kotlin.time.Instant?> = _lastEditedAt

    private val _recentlyDeleted = MutableStateFlow<Task?>(null)

    init {
        // Debounce-race fix: combine ensures the edit is applied to the correct task version.
        // If load hasn't completed yet, edits wait in the flow.
        scope.launch {
            combine(
                _latestTask.filterNotNull(),
                titleEdits.debounce(300.milliseconds),
            ) { task, title -> task to title }
                .collect { (task, title) ->
                    deps.updateTask(task.copy(title = title))
                        .onSuccess { _lastEditedAt.value = deps.clock.now() }
                        .onFailure { emitError("Save failed") }
                }
        }
        scope.launch {
            combine(
                _latestTask.filterNotNull(),
                descriptionEdits.debounce(300.milliseconds),
            ) { task, desc -> task to desc }
                .collect { (task, desc) ->
                    deps.updateTask(task.copy(description = desc.ifBlank { null }))
                        .onSuccess { _lastEditedAt.value = deps.clock.now() }
                        .onFailure { emitError("Save failed") }
                }
        }
    }

    val state: StateFlow<TaskDetailUiState> = deps.taskRepo.watchTask(taskId)
        .flatMapLatest { task ->
            if (task == null) {
                flowOf<TaskDetailUiState>(TaskDetailUiState.Error("Not found"))
            } else {
                val projectFlow = task.projectId?.let { pid ->
                    deps.projectsRepo.watchProject(pid)
                } ?: flowOf(null)

                val tagsFlow = deps.tagsRepo.watchTags(deps.currentUser.current.value)
                val checklistFlow = deps.checklistUseCase.watchChecklist(taskId.value)
                val reminderFlow = deps.reminderRepo.watchByTask(taskId, deps.currentUser.current)
                val attachmentsFlow = deps.attachmentsRepo.watchByTask(taskId, deps.currentUser.current)
                val subtasksFlow = deps.taskRepo.watchSubtasks(taskId, deps.currentUser.current)

                combine(
                    projectFlow,
                    tagsFlow,
                    checklistFlow,
                    reminderFlow,
                    attachmentsFlow,
                    subtasksFlow,
                ) { values ->
                    @Suppress("UNCHECKED_CAST")
                    val project = values[0] as com.singularity.todo.feature.projects.Project?
                    @Suppress("UNCHECKED_CAST")
                    val allTags = values[1] as List<com.singularity.todo.feature.tags.Tag>
                    @Suppress("UNCHECKED_CAST")
                    val checklist = values[2] as List<com.singularity.todo.feature.checklist.ChecklistItem>
                    @Suppress("UNCHECKED_CAST")
                    val reminders = values[3] as List<com.singularity.todo.feature.reminders.Reminder>
                    @Suppress("UNCHECKED_CAST")
                    val attachments = values[4] as List<com.singularity.todo.core.attachments.Attachment>
                    @Suppress("UNCHECKED_CAST")
                    val subtasks = values[5] as List<Task>

                    _latestTask.value = task
                    TaskDetailUiState.Loaded(
                        TaskDetailUi(
                            task = task,
                            project = project,
                            tags = allTags.filter { it.id in task.tags },
                            checklist = checklist,
                            reminders = reminders,
                            attachments = attachments,
                            subtasks = subtasks,
                        )
                    )
                }
            }
        }
        .catch { emit(TaskDetailUiState.Error(it.message ?: "Error")) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), TaskDetailUiState.Loading)

    /** Unified intent entry point. */
    fun onIntent(intent: TaskDetailIntent.Domain) {
        val current = _latestTask.value ?: return
        when (intent) {
            is TaskDetailIntent.Domain.ToggleComplete -> {
                val completed = current.completedAt == null
                val completedAt = if (completed) deps.clock.now() else null
                mutate(current, silent = true) { copy(completedAt = completedAt) }
            }
            is TaskDetailIntent.Domain.TitleChanged -> {
                titleEdits.tryEmit(intent.title)
            }
            is TaskDetailIntent.Domain.DescriptionChanged -> {
                descriptionEdits.tryEmit(intent.description)
            }
            is TaskDetailIntent.Domain.SetDueDate ->
                mutate(current) { copy(dueDate = intent.date) }
            is TaskDetailIntent.Domain.SetDueTime ->
                mutate(current) { copy(dueTime = intent.time?.toString()) }
            is TaskDetailIntent.Domain.SetPriority ->
                mutate(current) { copy(priority = intent.priority) }
            is TaskDetailIntent.Domain.SetProject ->
                mutate(current) { copy(projectId = intent.projectId) }
            is TaskDetailIntent.Domain.SetTags ->
                mutate(current) { copy(tags = intent.tagIds) }
            is TaskDetailIntent.Domain.RemoveTag ->
                mutate(current) { copy(tags = current.tags - intent.tagId) }
            is TaskDetailIntent.Domain.SetKind ->
                mutate(current, error = "Failed to set kind") { copy(kind = intent.kind) }
            is TaskDetailIntent.Domain.ToggleSomeday ->
                mutate(current, error = "Failed to set someday") { copy(someday = !someday) }
            is TaskDetailIntent.Domain.TogglePinned ->
                mutate(current) { copy(isPinned = !isPinned) }
            is TaskDetailIntent.Domain.ToggleChecklistItem -> {
                scope.launch {
                    deps.checklistUseCase.toggleItem(current.id.value, intent.item.id)
                        .onFailure { emitError("Toggle failed") }
                }
            }
            is TaskDetailIntent.Domain.DeleteChecklistItem -> {
                scope.launch {
                    deps.checklistUseCase.deleteItem(intent.id)
                        .onFailure { emitError("Delete failed") }
                }
            }
            is TaskDetailIntent.Domain.AddChecklistItem -> {
                scope.launch {
                    if (intent.title.isBlank()) return@launch
                    deps.checklistUseCase.addItem(current.id.value, intent.title.trim())
                        .onSuccess { scope.launch { _events.emit(TaskDetailUiEvent.Saved("Item added")) } }
                        .onFailure { emitError("Add failed") }
                }
            }
            is TaskDetailIntent.Domain.ToggleSubtask -> {
                val completed = intent.task.completedAt == null
                val completedAt = if (completed) deps.clock.now() else null
                mutate(intent.task, silent = true) { copy(completedAt = completedAt) }
            }
            is TaskDetailIntent.Domain.DeleteSubtask -> {
                scope.launch {
                    deps.taskRepo.softDelete(intent.task.id)
                        .onFailure { emitError("Delete subtask failed") }
                }
            }
            is TaskDetailIntent.Domain.AddSubtask -> {
                scope.launch {
                    if (intent.title.isBlank()) return@launch
                    deps.createTask(
                        com.singularity.todo.feature.tasks.domain.model.CreateTaskInput(
                            title = intent.title.trim(),
                            userId = deps.currentUser.current,
                            parentTaskId = current.id,
                        )
                    )
                        .onSuccess { scope.launch { _events.emit(TaskDetailUiEvent.Saved("Subtask added")) } }
                        .onFailure { emitError("Add subtask failed") }
                }
            }
            is TaskDetailIntent.Domain.SetReminder -> {
                scope.launch {
                    if (intent.offset == com.singularity.todo.core.reminders.ReminderOffset.AT_DUE) {
                        deps.reminderRepo.deleteByTask(current.id, deps.currentUser.current)
                            .onFailure { emitError("Failed to set reminder") }
                        return@launch
                    }
                    val now = deps.clock.now().toEpochMilliseconds()
                    val fireAt = computeFireAt(current.dueDate, current.dueTime, intent.offset, now)
                    val reminder = com.singularity.todo.feature.reminders.Reminder(
                        id = com.singularity.todo.feature.reminders.ReminderId.generate(),
                        taskId = current.id,
                        userId = deps.currentUser.current,
                        type = com.singularity.todo.feature.reminders.ReminderType.Gentle,
                        offsetMinutes = -intent.offset.minutes,
                        fireAt = fireAt,
                        recurringPattern = null,
                    )
                    deps.reminderRepo.upsert(reminder)
                        .onFailure { emitError("Failed to set reminder") }
                }
            }
            TaskDetailIntent.Domain.DeleteReminder -> {
                scope.launch {
                    deps.reminderRepo.deleteByTask(current.id, deps.currentUser.current)
                        .onFailure { emitError("Failed to remove reminder") }
                }
            }
            TaskDetailIntent.Domain.Delete -> {
                scope.launch {
                    _recentlyDeleted.value = current
                    deps.taskRepo.softDelete(current.id)
                        .onSuccess { _events.emit(TaskDetailUiEvent.UndoDelete(current.id)) }
                        .onFailure {
                            _recentlyDeleted.value = null
                            emitError("Delete failed")
                        }
                }
            }
            TaskDetailIntent.Domain.Archive -> {
                scope.launch {
                    deps.taskRepo.softDelete(current.id)
                        .onSuccess {
                            scope.launch {
                                _events.emit(TaskDetailUiEvent.Saved("Task archived"))
                                _events.emit(TaskDetailUiEvent.NavigateBack)
                            }
                        }
                        .onFailure { emitError("Archive failed") }
                }
            }
            TaskDetailIntent.Domain.Restore -> {
                scope.launch {
                    val task = _recentlyDeleted.value ?: return@launch
                    deps.taskRepo.restore(task.id)
                        .onSuccess {
                            _recentlyDeleted.value = null
                            scope.launch { _events.emit(TaskDetailUiEvent.Saved("Task restored")) }
                        }
                        .onFailure { emitError("Restore failed") }
                }
            }
        }
    }

    /** Non-blocking title edit — queues for debounced flush. */
    fun onTitleChange(value: String) {
        titleEdits.tryEmit(value)
    }

    /** Non-blocking description edit — queues for debounced flush. */
    fun onDescriptionChange(value: String) {
        descriptionEdits.tryEmit(value)
    }

    private fun mutate(
        current: Task,
        error: String = "Save failed",
        silent: Boolean = false,
        transform: Task.() -> Task,
    ) = scope.launch {
        deps.updateTask(current.transform())
            .onSuccess { if (!silent) _lastEditedAt.value = deps.clock.now() }
            .onFailure { emitError(error) }
    }

    private fun emitError(message: String) {
        scope.launch { _events.emit(TaskDetailUiEvent.Error(message)) }
    }

    private fun computeFireAt(
        dueDate: kotlinx.datetime.LocalDate?,
        dueTime: String?,
        offset: com.singularity.todo.core.reminders.ReminderOffset,
        nowEpochMs: Long,
    ): Long {
        if (dueDate == null) return nowEpochMs
        return com.singularity.todo.feature.tasks.domain.port.dueInstant(
            dueDate,
            dueTime,
            offset,
            deps.timeZoneProvider.current(),
        )
    }
}
