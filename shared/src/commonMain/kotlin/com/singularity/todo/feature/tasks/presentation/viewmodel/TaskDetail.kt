package com.singularity.todo.feature.tasks.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskDetailDeps
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUi
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiEvent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
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
    private val currentUser: ProfileAwareCurrentUser,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {

    init {
        addCloseable(scope)
    }

    /** Used for entity creation in mutation intents (caller-trust userId injection). */
    private val userId get() = currentUser.scopedUserId.value

    private val _events = Channel<TaskDetailUiEvent>(Channel.BUFFERED)
    val events: kotlinx.coroutines.flow.Flow<TaskDetailUiEvent> = _events.receiveAsFlow()

    // edits fire at most once per keystroke; buffer=4 absorbs up to 4-frame burst
    // during UI thread contention without dropping signals
    private val titleEdits = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)
    private val descriptionEdits = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)

    /** Visible for tests. TOCTOU: prefer to observe via state. */
    internal val _latestTask = MutableStateFlow<Task?>(null)

    /** Draft state — owned by VM, single source of truth for editable title/description. */
    val draftState = TaskDetailDraftState()

    private val _recentlyDeleted = MutableStateFlow<Task?>(null)

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
                        .onFailure { emitError("Save failed") }
                }
        }
    }

    val state: StateFlow<TaskDetailUiState> = combine(
        flowOf(taskId),
        _retryVersion,
    ) { id, _ -> id }
        .flatMapLatest { deps.taskRepo.observe(it) }
        .flatMapLatest { task ->
            // Update latestTask BEFORE combine starts — so debounce collectors always have fresh task
            _latestTask.value = task
            if (task == null) {
                flowOf<TaskDetailUiState>(TaskDetailUiState.Error("Not found"))
            } else {
                val projectFlow = task.projectId?.let { pid ->
                    deps.projectsRepo.observe(pid)
                } ?: flowOf(null)

                val tagsFlow = deps.tagsRepo.observeAll()
                val checklistFlow = deps.checklistRepository.watchByTask(taskId.value)
                val reminderFlow = deps.reminderRepo.watchByTask(taskId)
                val attachmentsFlow = deps.attachmentsRepo.watchByTask(taskId)
                val subtasksFlow = deps.taskRepo.observeSubtasks(taskId)
                val availableTasksFlow = deps.taskRepo.observeByFilter(com.singularity.todo.feature.tasks.domain.model.TaskFilter.All)
                    .map { all -> all.filter { !it.isTrashed && it.id != taskId } }

                combine(
                    projectFlow,
                    tagsFlow,
                    checklistFlow,
                    reminderFlow,
                    attachmentsFlow,
                    subtasksFlow,
                    availableTasksFlow,
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
                    val availableTasks = values[6] as List<Task>

                    @Suppress("UNCHECKED_CAST")
                    val draft = values[7] as TaskDetailDraft

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
                            dependsOn = task.dependsOn,
                            availableTasks = availableTasks,
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
                mutate(current) { copy(completedAt = completedAt) }
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
                        .onSuccess { _events.trySend(TaskDetailUiEvent.Saved("Dependencies updated")) }
                        .onFailure { emitError("Failed to set dependencies") }
                }
            }

            is TaskDetailIntent.Domain.ToggleChecklistItem -> {
                scope.launch {
                    deps.checklistRepository.toggleItem(current.id.value, intent.item.id)
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
                    deps.checklistRepository.addItem(current.id.value, intent.title.trim())
                        .onSuccess { _events.trySend(TaskDetailUiEvent.Saved("Item added")) }
                        .onFailure { emitError("Add failed") }
                }
            }

            is TaskDetailIntent.Domain.ToggleSubtask -> {
                val completed = intent.task.completedAt == null
                val completedAt = if (completed) deps.clock.now() else null
                mutate(intent.task) { copy(completedAt = completedAt) }
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
                            userId = userId,
                            parentTaskId = current.id,
                        ),
                    )
                        .onSuccess { _events.trySend(TaskDetailUiEvent.Saved("Subtask added")) }
                        .onFailure { emitError("Add subtask failed") }
                }
            }

            is TaskDetailIntent.Domain.SetReminder -> {
                scope.launch {
                    if (intent.offset == com.singularity.todo.core.reminders.ReminderOffset.AT_DUE) {
                        deps.reminderRepo.deleteByTask(current.id)
                            .onFailure { emitError("Failed to set reminder") }
                        return@launch
                    }
                    val now = deps.clock.now().toEpochMilliseconds()
                    val fireAt = computeFireAt(current.dueDate, current.dueTime, intent.offset, now)
                    val reminder = com.singularity.todo.feature.reminders.Reminder(
                        id = com.singularity.todo.feature.reminders.ReminderId.generate(),
                        taskId = current.id,
                        userId = userId,
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
                    deps.reminderRepo.deleteByTask(current.id)
                        .onFailure { emitError("Failed to remove reminder") }
                }
            }

            TaskDetailIntent.Domain.Delete -> {
                scope.launch {
                    _recentlyDeleted.value = current
                    deps.taskRepo.softDelete(current.id)
                        .onSuccess { _events.trySend(TaskDetailUiEvent.UndoDelete(current.id)) }
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
                            _events.trySend(TaskDetailUiEvent.Saved("Task archived"))
                            _events.trySend(TaskDetailUiEvent.NavigateBack)
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
                            _events.trySend(TaskDetailUiEvent.Saved("Task restored"))
                        }
                        .onFailure { emitError("Restore failed") }
                }
            }
        }
    }

    /** Re-triggers the watchTask subscription by bumping the retry version. */
    fun retry() {
        _retryVersion.value++
    }

    private fun mutate(
        current: Task,
        error: String = "Save failed",
        transform: Task.() -> Task,
    ) = scope.launch {
        deps.updateTask(current.transform())
            .onFailure { emitError(error) }
    }

    private fun emitError(message: String) {
        scope.launch { _events.trySend(TaskDetailUiEvent.Error(message)) }
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
