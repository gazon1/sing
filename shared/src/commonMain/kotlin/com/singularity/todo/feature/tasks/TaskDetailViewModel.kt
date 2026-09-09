package com.singularity.todo.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.attachments.Attachment
import com.singularity.todo.core.attachments.AttachmentRepository
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistUseCase
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.Project
import com.singularity.todo.feature.projects.ProjectsRepository
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderRepository
import com.singularity.todo.feature.reminders.ReminderType
import com.singularity.todo.feature.settings.ReminderOffset
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagsRepository
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
import kotlin.time.Instant

/** Combined read model for [TaskDetailScreen]. */
data class TaskDetailUi(
    val task: Task,
    val project: Project? = null,
    val tags: List<Tag> = emptyList(),
    val checklist: List<ChecklistItem> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    /** Direct child tasks (1-level hierarchy only). */
    val subtasks: List<Task> = emptyList(),
)

sealed interface TaskDetailUiState {
    data object Loading : TaskDetailUiState
    data class Loaded(val ui: TaskDetailUi) : TaskDetailUiState
    data class Error(val message: String) : TaskDetailUiState
}

/**
 * Dependencies for [TaskDetailViewModel] — reduces constructor parameter count
 * and makes DI registration more maintainable.
 */
data class TaskDetailDeps(
    val taskRepo: TaskRepository,
    val updateTask: UpdateTaskUseCase,
    val createTask: CreateTaskUseCase,
    val projectsRepo: ProjectsRepository,
    val tagsRepo: TagsRepository,
    val checklistUseCase: ChecklistUseCase,
    val reminderRepo: ReminderRepository,
    val attachmentsRepo: AttachmentRepository,
    val currentUser: ProfileAwareCurrentUser,
    val timeZoneProvider: TimeZoneProvider,
)

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class TaskDetailViewModel(
    private val deps: TaskDetailDeps,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    private val _taskId = MutableStateFlow<TaskId?>(null)
    private val _events = MutableSharedFlow<TaskDetailUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<TaskDetailUiEvent> = _events.asSharedFlow()

    /** Draft flows for inline-edit fields — debounced before hitting the repository. */
    private val titleDraft = MutableStateFlow<String?>(null)
    private val descriptionDraft = MutableStateFlow<String?>(null)

    /**
     * Cached latest task — avoids TOCTOU race when using `.first()` after debounce.
     * Updated whenever the combined state emits a new value.
     * ALL mutating operations must use this, not a snapshot from UI.
     */
    private val _latestTask = MutableStateFlow<Task?>(null)
    val latestTask: StateFlow<Task?> = _latestTask

    /**
     * Silent timestamp for debounced inline edits — does NOT emit Saved.
     * Exposed as [lastEditedAt] for the UI to render "Saved X ago" via [formatSavedRelative].
     */
    private val _lastEditedAt = MutableStateFlow<Instant?>(null)
    val lastEditedAt: StateFlow<Instant?> = _lastEditedAt

    /**
     * The most recently soft-deleted task, kept in memory so [Restore] can undo.
     * Cleared after a successful restore or when the snackbar timeout expires.
     */
    private val _recentlyDeleted = MutableStateFlow<Task?>(null)

    init {
        // Title debounce — reads _latestTask to avoid TOCTOU with concurrent remote edits.
        scope.launch {
            titleDraft
                .debounce(300.milliseconds)
                .filterNotNull()
                .collect { title ->
                    val current = _latestTask.value ?: return@collect
                    deps.updateTask(current.copy(title = title))
                        .onSuccess { _lastEditedAt.value = Clock.now() }
                        .onFailure { emitError("Save failed") }
                }
        }
        // Description debounce — same pattern.
        scope.launch {
            descriptionDraft
                .debounce(300.milliseconds)
                .filterNotNull()
                .collect { desc ->
                    val current = _latestTask.value ?: return@collect
                    deps.updateTask(current.copy(description = desc.ifBlank { null }))
                        .onSuccess { _lastEditedAt.value = Clock.now() }
                        .onFailure { emitError("Save failed") }
                }
        }
    }

    val state: StateFlow<TaskDetailUiState> = _taskId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf<TaskDetailUiState>(TaskDetailUiState.Loading)
            } else {
                val taskFlow = deps.taskRepo.watchTask(id)
                val projectFlow = taskFlow.map { task ->
                    val projectId = task?.projectId
                    if (projectId == null) flowOf<Project?>(null)
                    else deps.projectsRepo.watchProject(projectId)
                }.flatMapLatest { it }

                val tagsFlow = deps.tagsRepo.watchTags(deps.currentUser.current.value)
                val checklistFlow = deps.checklistUseCase.watchChecklist(id.value)
                val reminderFlow = deps.reminderRepo.watchByTask(id, deps.currentUser.current)
                val attachmentsFlow = deps.attachmentsRepo.watchByTask(id, deps.currentUser.current)
                val subtasksFlow = deps.taskRepo.watchSubtasks(id, deps.currentUser.current)

                combine(taskFlow, projectFlow, tagsFlow, checklistFlow, reminderFlow, attachmentsFlow, subtasksFlow) { values ->
                    @Suppress("UNCHECKED_CAST")
                    val task = values[0] as Task?
                    @Suppress("UNCHECKED_CAST")
                    val project = values[1] as Project?
                    @Suppress("UNCHECKED_CAST")
                    val allTags = values[2] as List<Tag>
                    @Suppress("UNCHECKED_CAST")
                    val checklist = values[3] as List<ChecklistItem>
                    @Suppress("UNCHECKED_CAST")
                    val reminders = values[4] as List<Reminder>
                    @Suppress("UNCHECKED_CAST")
                    val attachments = values[5] as List<Attachment>
                    @Suppress("UNCHECKED_CAST")
                    val subtasks = values[6] as List<Task>

                    _latestTask.value = task
                    if (task == null) {
                        TaskDetailUiState.Error("Not found")
                    } else {
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
        }
        .catch { emit(TaskDetailUiState.Error(it.message ?: "Error")) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), TaskDetailUiState.Loading)

    fun start(taskId: TaskId) {
        _taskId.value = taskId
    }

    // ─── Inline-edit callbacks ──────────────────────────────────────────────────

    fun onTitleChange(value: String) {
        titleDraft.value = value
    }

    fun onDescriptionChange(value: String) {
        descriptionDraft.value = value
    }

    // ─── Intent dispatcher ────────────────────────────────────────────────────

    /**
     * Единственный публичный метод для всех доменных операций.
     *
     * Routing-интенты ([TaskDetailIntent.OpenSheet], [TaskDetailIntent.NavigateTo*],
     * [TaskDetailIntent.Attachment]) обрабатываются экраном и сюда не попадают.
     */
    fun onIntent(intent: TaskDetailIntent.Domain) {
        val current = _latestTask.value ?: return
        when (intent) {
            // ── Hero ─────────────────────────────────────────────────────────────
            is TaskDetailIntent.Domain.ToggleComplete -> {
                val completed = current.completedAt == null
                val completedAt = if (completed) Clock.now() else null
                mutate(current, silent = true) { copy(completedAt = completedAt) }
            }
            is TaskDetailIntent.Domain.TitleChanged -> {
                // Debounced via titleDraft — don't call mutate here.
                // Handled by the init{} debounce collector.
            }
            is TaskDetailIntent.Domain.DescriptionChanged -> {
                // Debounced via descriptionDraft — don't call mutate here.
            }

            // ── Meta fields ───────────────────────────────────────────────────────
            is TaskDetailIntent.Domain.SetDueDate ->
                mutate(current) { copy(dueDate = intent.date) }
            is TaskDetailIntent.Domain.SetDueTime ->
                mutate(current) { copy(dueTime = intent.time?.takeIf { it.isNotBlank() }) }
            is TaskDetailIntent.Domain.SetPriority ->
                mutate(current) { copy(priority = intent.priority) }
            is TaskDetailIntent.Domain.SetProject ->
                mutate(current) { copy(projectId = intent.projectId) }

            // ── Tags ──────────────────────────────────────────────────────────────
            is TaskDetailIntent.Domain.SetTags ->
                mutate(current) { copy(tags = intent.tagIds) }
            is TaskDetailIntent.Domain.RemoveTag ->
                mutate(current) { copy(tags = current.tags - intent.tagId) }

            // ── Kind / Someday / Pin ─────────────────────────────────────────────
            is TaskDetailIntent.Domain.SetKind ->
                mutate(current, error = "Failed to set kind") { copy(kind = intent.kind) }
            is TaskDetailIntent.Domain.ToggleSomeday ->
                mutate(current, error = "Failed to set someday") { copy(someday = !someday) }
            is TaskDetailIntent.Domain.TogglePinned ->
                mutate(current) { copy(isPinned = !isPinned) }

            // ── Checklist ─────────────────────────────────────────────────────────
            is TaskDetailIntent.Domain.ToggleChecklistItem ->
                scope.launch {
                    deps.checklistUseCase.toggleItem(current.id.value, intent.item.id)
                        .onFailure { emitError("Toggle failed") }
                }
            is TaskDetailIntent.Domain.DeleteChecklistItem ->
                scope.launch {
                    deps.checklistUseCase.deleteItem(intent.id)
                        .onFailure { emitError("Delete failed") }
                }
            is TaskDetailIntent.Domain.AddChecklistItem ->
                scope.launch {
                    if (intent.title.isBlank()) return@launch
                    deps.checklistUseCase.addItem(current.id.value, intent.title.trim())
                        .onSuccess { scope.launch { _events.emit(TaskDetailUiEvent.Saved("Item added")) } }
                        .onFailure { emitError("Add failed") }
                }

            // ── Subtasks ─────────────────────────────────────────────────────────
            is TaskDetailIntent.Domain.ToggleSubtask -> {
                val completed = intent.task.completedAt == null
                val completedAt = if (completed) Clock.now() else null
                mutate(intent.task, silent = true) { copy(completedAt = completedAt) }
            }
            is TaskDetailIntent.Domain.DeleteSubtask ->
                scope.launch {
                    deps.taskRepo.softDelete(intent.task.id)
                        .onFailure { emitError("Delete subtask failed") }
                }
            is TaskDetailIntent.Domain.AddSubtask ->
                scope.launch {
                    if (intent.title.isBlank()) return@launch
                    deps.createTask(
                        CreateTaskInput(
                            title = intent.title.trim(),
                            userId = deps.currentUser.current,
                            parentTaskId = current.id,
                        )
                    )
                        .onSuccess { scope.launch { _events.emit(TaskDetailUiEvent.Saved("Subtask added")) } }
                        .onFailure { emitError("Add subtask failed") }
                }

            // ── Reminders ────────────────────────────────────────────────────────
            is TaskDetailIntent.Domain.SetReminder -> {
                scope.launch {
                    if (intent.offset == ReminderOffset.AT_DUE) {
                        deps.reminderRepo.deleteByTask(current.id, deps.currentUser.current)
                            .onFailure { emitError("Failed to set reminder") }
                        return@launch
                    }
                    val now = Clock.now().toEpochMilliseconds()
                    val fireAt = computeFireAt(current.dueDate, current.dueTime, intent.offset, now)
                    val reminder = Reminder(
                        id = ReminderId.generate(),
                        taskId = current.id,
                        userId = deps.currentUser.current,
                        type = ReminderType.Gentle,
                        offsetMinutes = -intent.offset.minutes,
                        fireAt = fireAt,
                        recurringPattern = null,
                    )
                    deps.reminderRepo.upsert(reminder)
                        .onFailure { emitError("Failed to set reminder") }
                }
            }
            TaskDetailIntent.Domain.DeleteReminder ->
                scope.launch {
                    deps.reminderRepo.deleteByTask(current.id, deps.currentUser.current)
                        .onFailure { emitError("Failed to remove reminder") }
                }

            // ── Lifecycle ─────────────────────────────────────────────────────────
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

    // ─── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Applies a mutation to [current] via [transform] and persists via [updateTask].
     * Uses [_latestTask] as the source of truth — not a snapshot from UI — to avoid TOCTOU.
     *
     * @param current  the task to transform; must be the same reference as [_latestTask].
     * @param error    user-facing message on failure.
     * @param silent   if true, [_lastEditedAt] is NOT updated (e.g. for toggle-complete).
     * @param transform  field mutations to apply.
     */
    private fun mutate(
        current: Task,
        error: String = "Save failed",
        silent: Boolean = false,
        transform: Task.() -> Task,
    ) = scope.launch {
        deps.updateTask(current.transform())
            .onSuccess { if (!silent) _lastEditedAt.value = Clock.now() }
            .onFailure { emitError(error) }
    }

    private fun emitError(message: String) {
        scope.launch { _events.emit(TaskDetailUiEvent.Error(message)) }
    }

    private fun computeFireAt(
        dueDate: kotlinx.datetime.LocalDate?,
        dueTime: String?,
        offset: ReminderOffset,
        nowEpochMs: Long,
    ): Long {
        if (dueDate == null) return nowEpochMs
        return dueInstant(dueDate, dueTime, offset, deps.timeZoneProvider.current())
    }
}
