package com.singularity.todo.feature.tasks.presentation.viewmodel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskAiAction
import com.singularity.todo.feature.tasks.domain.model.TaskDetailDeps
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUi
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiEvent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<TaskDetailUiState, TaskDetailIntent.Domain, TaskDetailUiEvent>(
    initialState = TaskDetailUiState.Loading,
    scope = scope,
) {
    override val vmScope = scope

    // edits fire at most once per keystroke; buffer=4 absorbs up to 4-frame burst
    // during UI thread contention without dropping signals
    private val titleEdits = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)
    private val descriptionEdits = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)

    /** Visible for tests. TOCTOU: prefer to observe via state. */
    internal val _latestTask = MutableStateFlow<Task?>(null)

    /** Draft state — owned by VM, single source of truth for editable title/description. */
    val draftState = TaskDetailDraftState()

    /**
     * Type-safe intermediate containers for the nested combine chain.
     * Eliminates the original 8x @Suppress("UNCHECKED_CAST") in the 8-flow combine.
     */
    private data class Meta(
        val project: com.singularity.todo.feature.projects.domain.model.Project?,
        val allTags: List<com.singularity.todo.feature.tags.Tag>,
    )

    private data class Content(
        val checklist: List<com.singularity.todo.feature.checklist.ChecklistItem>,
        val reminders: List<com.singularity.todo.feature.reminders.Reminder>,
        val attachments: List<com.singularity.todo.core.attachments.Attachment>,
        val subtasks: List<Task>,
        val available: List<Task>,
    )

    private data class AllData(val meta: Meta, val content: Content, val draft: TaskDetailDraft)

    private val _recentlyDeleted = MutableStateFlow<Task?>(null)
    private val _aiRunning = MutableStateFlow(false)

    /** Backlinks — loaded when task changes. */
    private val _linkedNotes = MutableStateFlow<List<com.singularity.todo.feature.notes.Note>>(emptyList())
    private val _linkedTasks = MutableStateFlow<List<Task>>(emptyList())

    /** Incremented on each retry() call to restart the watchTask subscription. */
    private val _retryVersion = MutableStateFlow(0)

    init {
        // Debounce-race fix: combine ensures the edit is applied to the correct task version.
        // If load hasn't completed yet, edits wait in the flow.
        vmScope.launch {
            combine(
                _latestTask.filterNotNull(),
                titleEdits.debounce(deps.debounceMs.milliseconds),
            ) { task, title -> task to title }
                .collect { (task, title) ->
                    deps.updateTask(task.copy(title = title))
                        .onFailure { emitError("Save failed") }
                }
        }
        vmScope.launch {
            combine(
                _latestTask.filterNotNull(),
                descriptionEdits.debounce(deps.debounceMs.milliseconds),
            ) { task, desc -> task to desc }
                .collect { (task, desc) ->
                    deps.updateTask(task.copy(description = desc.ifBlank { null }))
                        .onFailure { emitError("Save failed") }
                }
        }
        // Load backlinks when the observed task changes.
        vmScope.launch {
            _latestTask.filterNotNull().collect { task ->
                val linkRepo = deps.linkRepo ?: return@collect
                val notes = linkRepo.getNotesLinkingToTask(task.id.value)
                val tasks = linkRepo.getBacklinkTasks(task.id.value)
                _linkedNotes.value = notes
                _linkedTasks.value = tasks
            }
        }
    }

    init {
        vmScope.launch {
            combine(
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
                        val availableTasksFlow = deps.taskRepo.observeByFilter(
                            com.singularity.todo.feature.tasks.domain.model.TaskFilter.All,
                        )
                            .map { all -> all.filter { !it.isTrashed && it.id != taskId } }

                        // Level 1: Meta (project + tags)
                        val metaFlow = combine(projectFlow, tagsFlow) { project, allTags ->
                            Meta(project, allTags)
                        }

                        // Level 2: Content (checklist + reminders + attachments + subtasks + available)
                        val contentFlow = combine(
                            checklistFlow,
                            reminderFlow,
                            attachmentsFlow,
                            subtasksFlow,
                            availableTasksFlow,
                        ) { checklist, reminders, attachments, subtasks, available ->
                            Content(checklist, reminders, attachments, subtasks, available)
                        }

                        // Level 3: All combined (Meta + Content + Draft)
                        val allFlow = combine(metaFlow, contentFlow, draftState.state) { meta, content, draft ->
                            AllData(meta, content, draft)
                        }

                        allFlow.combine(flowOf(task)) { all, t ->
                            // Seed from loaded task — idempotent, won't overwrite user's active edits.
                            draftState.seed(t.title, t.description ?: "")

                            TaskDetailUiState.Loaded(
                                TaskDetailUi(
                                    task = t,
                                    titleDraft = all.draft.title,
                                    descriptionDraft = all.draft.description,
                                    project = all.meta.project,
                                    tags = all.meta.allTags.filter { it.id in t.tags },
                                    checklist = all.content.checklist,
                                    reminders = all.content.reminders,
                                    attachments = all.content.attachments,
                                    subtasks = all.content.subtasks,
                                    dependsOn = t.dependsOn,
                                    availableTasks = all.content.available,
                                    linkedNotes = _linkedNotes.value,
                                    linkedTasks = _linkedTasks.value,
                                ),
                            )
                        }
                    }
                }
                .catch { setState(TaskDetailUiState.Error(it.toMessage())) }
                .collect { setState(it) }
        }
    }

    /** Unified intent entry point. */
    override fun onIntent(intent: TaskDetailIntent.Domain) {
        when (intent) {
            is TaskDetailIntent.Domain.ToggleComplete -> {
                val task = _latestTask.value ?: return
                val completed = task.completedAt == null
                if (completed && task.recurrence != null) {
                    // Recurring task: use CompleteRecurringTaskUseCase to roll forward
                    vmScope.launch {
                        val t = _latestTask.value ?: return@launch
                        deps.completeRecurring(t.id)
                            .onSuccess {
                                emit(TaskDetailUiEvent.Saved("Repeating: next occurrence set"))
                            }
                            .onFailure { emitError("Failed to complete recurring task") }
                    }
                } else {
                    val completedAt = if (completed) deps.clock.now() else null
                    mutate { copy(completedAt = completedAt) }
                }
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
                mutate { copy(dueDate = intent.date) }

            is TaskDetailIntent.Domain.SetDueTime ->
                mutate { copy(dueTime = intent.time) }

            is TaskDetailIntent.Domain.SetPriority ->
                mutate { copy(priority = intent.priority) }

            is TaskDetailIntent.Domain.SetProject ->
                mutate { copy(projectId = intent.projectId) }

            is TaskDetailIntent.Domain.SetTags ->
                mutate { copy(tags = intent.tagIds) }

            is TaskDetailIntent.Domain.RemoveTag ->
                mutate { copy(tags = tags - intent.tagId) }

            is TaskDetailIntent.Domain.SetKind ->
                mutate(error = "Failed to set kind") { copy(kind = intent.kind) }

            is TaskDetailIntent.Domain.ToggleSomeday ->
                mutate(error = "Failed to set someday") { copy(someday = !someday) }

            is TaskDetailIntent.Domain.TogglePinned ->
                mutate { copy(isPinned = !isPinned) }

            is TaskDetailIntent.Domain.SetDependencies -> {
                vmScope.launch {
                    val task = _latestTask.value ?: return@launch
                    deps.taskRepo.setDependencies(task.id, intent.dependsOn)
                        .onSuccess { emit(TaskDetailUiEvent.Saved("Dependencies updated")) }
                        .onFailure { emitError("Failed to set dependencies") }
                }
            }

            is TaskDetailIntent.Domain.SetRecurrence -> {
                mutate(error = "Failed to set recurrence") {
                    copy(recurrence = intent.spec)
                }
            }

            is TaskDetailIntent.Domain.ToggleChecklistItem -> {
                vmScope.launch {
                    val task = _latestTask.value ?: return@launch
                    deps.checklistRepository.toggleItem(task.id.value, intent.item.id)
                        .onFailure { emitError("Toggle failed") }
                }
            }

            is TaskDetailIntent.Domain.DeleteChecklistItem -> {
                vmScope.launch {
                    deps.checklistRepository.delete(intent.id)
                        .onFailure { emitError("Delete failed") }
                }
            }

            is TaskDetailIntent.Domain.AddChecklistItem -> {
                vmScope.launch {
                    if (intent.title.isBlank()) return@launch
                    val task = _latestTask.value ?: return@launch
                    deps.checklistRepository.addItem(task.id.value, intent.title.trim())
                        .onSuccess { emit(TaskDetailUiEvent.Saved("Item added")) }
                        .onFailure { emitError("Add failed") }
                }
            }

            is TaskDetailIntent.Domain.ToggleSubtask -> {
                val completed = intent.task.completedAt == null
                val completedAt = if (completed) deps.clock.now() else null
                mutate(intent.task) { copy(completedAt = completedAt) }
            }

            is TaskDetailIntent.Domain.DeleteSubtask -> {
                vmScope.launch {
                    deps.taskRepo.softDelete(intent.task.id)
                        .onFailure { emitError("Delete subtask failed") }
                }
            }

            is TaskDetailIntent.Domain.AddSubtask -> {
                vmScope.launch {
                    if (intent.title.isBlank()) return@launch
                    val task = _latestTask.value ?: return@launch
                    deps.createTask(
                        com.singularity.todo.feature.tasks.domain.model.CreateTaskInput(
                            title = intent.title.trim(),
                            parentTaskId = task.id,
                        ),
                    )
                        .onSuccess { emit(TaskDetailUiEvent.Saved("Subtask added")) }
                        .onFailure { emitError("Add subtask failed") }
                }
            }

            is TaskDetailIntent.Domain.SetReminder -> {
                vmScope.launch {
                    val task = _latestTask.value ?: return@launch
                    val ambientUserId = deps.taskRepo.currentUserId()
                    if (intent.offset == com.singularity.todo.core.reminders.ReminderOffset.AT_DUE) {
                        deps.reminderScheduler.cancelByTask(task.id, ambientUserId)
                        deps.reminderRepo.deleteByTask(task.id)
                            .onFailure { emitError("Failed to set reminder") }
                        return@launch
                    }
                    val now = deps.clock.now().toEpochMilliseconds()
                    val fireAt = computeFireAt(task.dueDate, task.dueTime, intent.offset, now)
                    val reminder = com.singularity.todo.feature.reminders.Reminder(
                        id = com.singularity.todo.feature.reminders.ReminderId.generate(),
                        taskId = task.id,
                        userId = ambientUserId,
                        type = com.singularity.todo.feature.reminders.ReminderType.Gentle,
                        offsetMinutes = -intent.offset.minutes,
                        fireAt = fireAt,
                        recurringPattern = null,
                    )
                    deps.reminderRepo.upsert(reminder)
                        .onSuccess { deps.reminderScheduler.schedule(reminder) }
                        .onFailure { emitError("Failed to set reminder") }
                }
            }

            TaskDetailIntent.Domain.DeleteReminder -> {
                vmScope.launch {
                    val task = _latestTask.value ?: return@launch
                    val ambientUserId = deps.taskRepo.currentUserId()
                    deps.reminderScheduler.cancelByTask(task.id, ambientUserId)
                    deps.reminderRepo.deleteByTask(task.id)
                        .onFailure { emitError("Failed to remove reminder") }
                }
            }

            TaskDetailIntent.Domain.Delete -> {
                vmScope.launch {
                    val task = _latestTask.value ?: return@launch
                    _recentlyDeleted.value = task
                    val ambientUserId = deps.taskRepo.currentUserId()
                    deps.reminderScheduler.cancelByTask(task.id, ambientUserId)
                    deps.taskRepo.softDelete(task.id)
                        .onSuccess { emit(TaskDetailUiEvent.UndoDelete(task.id)) }
                        .onFailure {
                            _recentlyDeleted.value = null
                            emitError("Delete failed")
                        }
                }
            }

            TaskDetailIntent.Domain.Archive -> {
                vmScope.launch {
                    val task = _latestTask.value ?: return@launch
                    deps.taskRepo.softDelete(task.id)
                        .onSuccess {
                            emit(TaskDetailUiEvent.Saved("Task archived"))
                            emit(TaskDetailUiEvent.NavigateBack)
                        }
                        .onFailure { emitError("Archive failed") }
                }
            }

            TaskDetailIntent.Domain.Restore -> {
                vmScope.launch {
                    val task = _recentlyDeleted.value ?: return@launch
                    deps.taskRepo.restore(task.id)
                        .onSuccess {
                            _recentlyDeleted.value = null
                            emit(TaskDetailUiEvent.Saved("Task restored"))
                        }
                        .onFailure { emitError("Restore failed") }
                }
            }

            is TaskDetailIntent.Domain.SetStartDate ->
                mutate { copy(startDate = intent.date) }

            is TaskDetailIntent.Domain.SetStartTime ->
                mutate { copy(startTime = intent.time) }

            is TaskDetailIntent.Domain.AddUrlAttachment -> {
                vmScope.launch {
                    val task = _latestTask.value ?: return@launch
                    deps.attachmentsRepo.addUrlAttachment(task.id, intent.url, intent.title)
                        .onSuccess { emit(TaskDetailUiEvent.Saved("Attachment added")) }
                        .onFailure { emitError("Failed to add attachment") }
                }
            }

            is TaskDetailIntent.Domain.DeleteAttachment -> {
                vmScope.launch {
                    deps.attachmentsRepo.delete(intent.id)
                        .onSuccess { emit(TaskDetailUiEvent.Saved("Attachment deleted")) }
                        .onFailure { emitError("Failed to delete attachment") }
                }
            }

            is TaskDetailIntent.Domain.RunAiAction -> {
                vmScope.launch {
                    val task = _latestTask.value ?: return@launch
                    _aiRunning.value = true
                    val result = when (intent.action) {
                        TaskAiAction.RefineTitle ->
                            deps.refineTask
                            ?.invoke(task.title, task.description)
                            ?.map { newTitle ->
                                deps.updateTask(task.copy(title = newTitle))
                                emit(TaskDetailUiEvent.Saved("Title refined"))
                            }
                            ?: Result.failure(IllegalStateException("RefineTaskUseCase not available"))

                        TaskAiAction.GenerateDescription ->
                            deps.generateDescription
                            ?.invoke(task.title)
                            ?.map { desc ->
                                deps.updateTask(task.copy(description = desc))
                                emit(TaskDetailUiEvent.Saved("Description generated"))
                            }
                            ?: Result.failure(IllegalStateException("GenerateDescriptionUseCase not available"))

                        TaskAiAction.GenerateChecklist ->
                            deps.generateChecklist
                            ?.invoke(task.title, task.description)
                            ?.map { steps ->
                                steps.forEach { step ->
                                    deps.createTask(
                                        com.singularity.todo.feature.tasks.domain.model.CreateTaskInput(
                                            title = step,
                                            parentTaskId = task.id,
                                        ),
                                    )
                                }
                                emit(TaskDetailUiEvent.Saved("${steps.size} checklist items added"))
                            }
                            ?: Result.failure(IllegalStateException("GenerateChecklistUseCase not available"))

                        TaskAiAction.Decompose ->
                            deps.decomposeTask
                            ?.invoke(task.title, task.description)
                            ?.map { subTasks ->
                                subTasks.forEach { title ->
                                    deps.createTask(
                                        com.singularity.todo.feature.tasks.domain.model.CreateTaskInput(
                                            title = title,
                                            parentTaskId = task.id,
                                        ),
                                    )
                                }
                                emit(TaskDetailUiEvent.Saved("${subTasks.size} subtasks created"))
                            }
                            ?: Result.failure(IllegalStateException("DecomposeTaskUseCase not available"))

                        TaskAiAction.SuggestTime ->
                            deps.pickTime
                            ?.invoke(task.title, task.description)
                            ?.map { suggestedTime ->
                                emit(TaskDetailUiEvent.Saved("Suggested: $suggestedTime"))
                            }
                            ?: Result.failure(IllegalStateException("PickTimeUseCase not available"))
                    }
                    _aiRunning.value = false
                    result.onFailure { emitError("AI action failed: ${it.message}") }
                }
            }
        }
    }

    /** Re-triggers the watchTask subscription by bumping the retry version. */
    fun retry() {
        _retryVersion.value++
    }

    private fun mutate(error: String = "Save failed", transform: Task.() -> Task) = vmScope.launch {
        val current = _latestTask.value ?: return@launch
        deps.updateTask(current.transform())
            .onFailure { emitError(error) }
    }

    /** Overload for when we already have a Task reference (e.g., ToggleSubtask with intent.task) */
    private fun mutate(task: Task, error: String = "Save failed", transform: Task.() -> Task) = vmScope.launch {
        deps.updateTask(task.transform())
            .onFailure { emitError(error) }
    }

    private fun emitError(message: String) {
        vmScope.launch { emit(TaskDetailUiEvent.Error(message)) }
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
