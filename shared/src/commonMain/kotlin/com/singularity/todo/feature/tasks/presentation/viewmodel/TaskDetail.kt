package com.singularity.todo.feature.tasks.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskDetailDeps
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUi
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiEvent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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

/**
 * Task detail screen ViewModel.
 *
 * Owns: single task with checklist, reminders, attachments, subtasks.
 * Triggers: status/complete toggle, inline edit changes, checklist mutations,
 *   reminder add/remove, attachment add/remove, subtask create/delete, delete/restore.
 * One-shot events: [TaskDetailUiEvent.NavigateBack], [TaskDetailUiEvent.ShowSnackbar],
 *   [TaskDetailUiEvent.OpenReminderPicker], [TaskDetailUiEvent.OpenKindSheet].
 *
 * @see TaskDetailUiState
 * @see TaskDetailIntent
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class TaskDetailViewModel(
    private val deps: TaskDetailDeps,
    private val taskId: TaskId,
    private val scope: CoroutineScope,
) : ViewModel() {

    /** Production constructor — Koin uses this. */
    constructor(
        deps: TaskDetailDeps,
        taskId: TaskId,
    ) : this(
        deps = deps,
        taskId = taskId,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private val _events = MutableSharedFlow<TaskDetailUiEvent>(replay = 0, extraBufferCapacity = 4)
    val events: SharedFlow<TaskDetailUiEvent> = _events.asSharedFlow()

    // edits fire at most once per keystroke; buffer=4 absorbs up to 4-frame burst
    // during UI thread contention without dropping signals
    private val titleEdits = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)
    private val descriptionEdits = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)

    /** Visible for tests. TOCTOU: prefer to observe via state. */
    internal val _latestTask = MutableStateFlow<Task?>(null)

    /** Draft state — owned by VM, single source of truth for editable title/description. */
    val draftState = TaskDetailDraftState()

    private val _lastEditedAt = MutableStateFlow<kotlin.time.Instant?>(null)
    val lastEditedAt: StateFlow<kotlin.time.Instant?> = _lastEditedAt

    private val _recentlyDeleted = MutableStateFlow<Task?>(null)

    /** Public for TaskDetailViewContent to show undo snackbar after delete. */
    val recentlyDeleted: StateFlow<Task?> = _recentlyDeleted.asStateFlow()

    /** Incremented on each retry() call to restart the watchTask subscription. */
    private val _retryVersion = MutableStateFlow(0)

    init {
        // Debounce-race fix: combine ensures the edit is applied to the correct task version.
        // If load hasn't completed yet, edits wait in the flow.
        scope.launch {
            combine(
                _latestTask.filterNotNull(),
                titleEdits.debounce(deps.debounceMs.milliseconds),
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
                descriptionEdits.debounce(deps.debounceMs.milliseconds),
            ) { task, desc -> task to desc }
                .collect { (task, desc) ->
                    deps.updateTask(task.copy(description = desc.ifBlank { null }))
                        .onSuccess { _lastEditedAt.value = deps.clock.now() }
                        .onFailure { emitError("Save failed") }
                }
        }
    }

    val state: StateFlow<TaskDetailUiState> = combine(
        flowOf(taskId),
        _retryVersion,
    ) { id, _ -> id }
        .flatMapLatest { deps.taskRepo.watchTask(it) }
        .flatMapLatest { task ->
            // Update latestTask BEFORE combine starts — so debounce collectors always have fresh task
            _latestTask.value = task
            if (task == null) {
                flowOf<TaskDetailUiState>(TaskDetailUiState.Error("Not found"))
            } else {
                val projectFlow = task.projectId?.let { pid ->
                    deps.projectsRepo.watchProject(pid)
                } ?: flowOf(null)

                val tagsFlow = deps.tagsRepo.watchTags(deps.currentUser.current.value)
                val checklistFlow = deps.checklistRepository.watchByTask(taskId.value)
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
                    draftState.state,
                ) { values ->
                    @Suppress("UNCHECKED_CAST")
                    val project = values[0] as com.singularity.todo.feature.projects.domain.model.Project?

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

                    @Suppress("UNCHECKED_CAST")
                    val draft = values[6] as TaskDetailDraft

                    // Seed from loaded task — idempotent, won't overwrite user's active edits.
                    draftState.seed(task.title, task.description ?: "")

                    TaskDetailUiState.Loaded(
                        TaskDetailUi(
                            task = task,
                            titleDraft = draft.title,
                            descriptionDraft = draft.description,
                            project = project,
                            tags = allTags.filter { it.id in task.tags },
                            checklist = checklist,
                            reminders = reminders,
                            attachments = attachments,
                            subtasks = subtasks,
                        ),
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
                draftState.setTitle(intent.title)
                titleEdits.tryEmit(intent.title)
            }

            is TaskDetailIntent.Domain.DescriptionChanged -> {
                draftState.setDescription(intent.description)
                descriptionEdits.tryEmit(intent.description)
            }

            is TaskDetailIntent.Domain.SetDueDate ->
                mutate(current) { copy(dueDate = intent.date) }

            is TaskDetailIntent.Domain.SetDueTime ->
                mutate(current) { copy(dueTime = intent.time) }

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

            is TaskDetailIntent.Domain.SetDependencies -> {
                scope.launch {
                    deps.taskRepo.setDependencies(current.id, intent.dependsOn)
                        .onFailure { emitError("Failed to set dependencies") }
                }
            }

            is TaskDetailIntent.Domain.ToggleChecklistItem -> {
                scope.launch {
                    deps.checklistUseCase.toggleItem(current.id.value, intent.item.id)
                        .onFailure { emitError("Toggle failed") }
                }
            }

            is TaskDetailIntent.Domain.DeleteChecklistItem -> {
                scope.launch {
                    deps.checklistRepository.delete(intent.id)
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
                        ),
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
        draftState.setTitle(value)
        titleEdits.tryEmit(value)
    }

    /** Non-blocking description edit — queues for debounced flush. */
    fun onDescriptionChange(value: String) {
        draftState.setDescription(value)
        descriptionEdits.tryEmit(value)
    }

    /** Re-triggers the watchTask subscription by bumping the retry version. */
    fun retry() {
        _retryVersion.value++
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
        dueTime: kotlinx.datetime.LocalTime?,
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
