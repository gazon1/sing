package com.singularity.todo.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.attachments.Attachment
import com.singularity.todo.core.attachments.AttachmentRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.checklist.ChecklistUseCase
import com.singularity.todo.feature.projects.Project
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.projects.ProjectsRepository
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderRepository
import com.singularity.todo.feature.reminders.ReminderType
import com.singularity.todo.feature.settings.ReminderOffset
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.TagsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlin.time.Instant
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.platform.TimeZoneProvider
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** Combined read model for [TaskDetailScreen]. */
data class TaskDetailUi(
    val task: Task,
    val project: Project? = null,
    val tags: List<Tag> = emptyList(),
    val checklist: List<ChecklistItem> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    /** Continuous draft for the inline checklist add-field. */
    val checklistDraft: String = "",
)

sealed interface TaskDetailUiState {
    data object Loading : TaskDetailUiState
    data class Loaded(val ui: TaskDetailUi) : TaskDetailUiState
    data class Error(val message: String) : TaskDetailUiState
}

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class TaskDetailViewModel(
    private val taskRepo: TaskRepository,
    private val updateTask: UpdateTaskUseCase,
    private val projectsRepo: ProjectsRepository,
    private val tagsRepo: TagsRepository,
    private val checklistUseCase: ChecklistUseCase,
    private val reminderRepo: ReminderRepository,
    private val attachmentsRepo: AttachmentRepository,
    private val currentUser: ProfileAwareCurrentUser,
    private val timeZoneProvider: TimeZoneProvider,
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
     * Silent timestamp for debounced inline edits — does NOT emit Saved.
     * Exposed as [lastEditedAt] for the UI to render "Saved X ago" via [formatSavedRelative].
     */
    private val _lastEditedAt = MutableStateFlow<Instant?>(null)
    val lastEditedAt: StateFlow<Instant?> = _lastEditedAt

    /** Collectors for debounced drafts. Each fires after 300 ms of inactivity. */
    init {
        scope.launch {
            titleDraft
                .debounce(300)
                .filterNotNull()
                .collect { title ->
                    val taskId = _taskId.value ?: return@collect
                    val current = taskRepo.watchTask(taskId).filterNotNull().first()
                    updateTask(current.copy(title = title))
                        .onSuccess { _lastEditedAt.value = kotlin.time.Clock.System.now() }
                        .onFailure { _events.emit(TaskDetailUiEvent.Error(it.message ?: "Save failed")) }
                }
        }
        scope.launch {
            descriptionDraft
                .debounce(300)
                .filterNotNull()
                .collect { desc ->
                    val taskId = _taskId.value ?: return@collect
                    val current = taskRepo.watchTask(taskId).filterNotNull().first()
                    updateTask(current.copy(description = desc.ifBlank { null }))
                        .onSuccess { _lastEditedAt.value = kotlin.time.Clock.System.now() }
                        .onFailure { _events.emit(TaskDetailUiEvent.Error(it.message ?: "Save failed")) }
                }
        }
    }

    val state: StateFlow<TaskDetailUiState> = _taskId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf<TaskDetailUiState>(TaskDetailUiState.Loading)
            } else {
                val taskFlow = taskRepo.watchTask(id)
                val projectFlow = taskFlow.map { task ->
                    val projectId = task?.projectId
                    if (projectId == null) flowOf<Project?>(null)
                    else projectsRepo.watchProject(projectId)
                }.flatMapLatest { it }

                val tagsFlow = tagsRepo.watchTags(currentUser.current.value)
                val checklistFlow = checklistUseCase.watchChecklist(id.value)
                val reminderFlow = reminderRepo.watchByTask(id, currentUser.current)
                val attachmentsFlow = attachmentsRepo.watchByTask(id, currentUser.current)

                combine(taskFlow, projectFlow, tagsFlow, checklistFlow, reminderFlow, attachmentsFlow) { values ->
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
                                checklistDraft = "",
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

    // ─── Field setters ─────────────────────────────────────────────────────────

    fun setTitle(current: Task, value: String) = scope.launch {
        updateTask(current.copy(title = value.takeIf { it.isNotBlank() } ?: current.title))
            .onSuccess { _events.emit(TaskDetailUiEvent.Saved("Title updated")) }
            .onFailure { _events.emit(TaskDetailUiEvent.Error(it.message ?: "Save failed")) }
    }

    fun setDescription(current: Task, value: String) = scope.launch {
        updateTask(current.copy(description = value.ifBlank { null }))
            .onSuccess { _events.emit(TaskDetailUiEvent.Saved("Description updated")) }
            .onFailure { _events.emit(TaskDetailUiEvent.Error(it.message ?: "Save failed")) }
    }

    fun setDueDate(current: Task, date: kotlinx.datetime.LocalDate?) = scope.launch {
        updateTask(current.copy(dueDate = date))
            .onSuccess { _events.emit(TaskDetailUiEvent.Saved("Date updated")) }
            .onFailure { _events.emit(TaskDetailUiEvent.Error(it.message ?: "Save failed")) }
    }

    fun setDueTime(current: Task, time: String?) = scope.launch {
        updateTask(current.copy(dueTime = time?.takeIf { it.isNotBlank() }))
            .onSuccess { _events.emit(TaskDetailUiEvent.Saved("Time updated")) }
            .onFailure { _events.emit(TaskDetailUiEvent.Error(it.message ?: "Save failed")) }
    }

    fun setPriority(current: Task, priority: TaskPriority) = scope.launch {
        updateTask(current.copy(priority = priority))
            .onSuccess { _events.emit(TaskDetailUiEvent.Saved("Priority updated")) }
            .onFailure { _events.emit(TaskDetailUiEvent.Error(it.message ?: "Save failed")) }
    }

    fun setProject(current: Task, projectId: ProjectId?) = scope.launch {
        updateTask(current.copy(projectId = projectId))
            .onSuccess { _events.emit(TaskDetailUiEvent.Saved("Project updated")) }
            .onFailure { _events.emit(TaskDetailUiEvent.Error(it.message ?: "Save failed")) }
    }

    fun setPinned(current: Task, pinned: Boolean) = scope.launch {
        updateTask(current.copy(isPinned = pinned))
            .onSuccess { _events.emit(TaskDetailUiEvent.Saved(if (pinned) "Task pinned" else "Task unpinned")) }
            .onFailure { _events.emit(TaskDetailUiEvent.Error(it.message ?: "Save failed")) }
    }

    fun setCompleted(current: Task, completed: Boolean) = scope.launch {
        val completedAt = if (completed) {
            Clock.now()
        } else {
            null
        }
        updateTask(current.copy(completedAt = completedAt))
            .onFailure { _events.emit(TaskDetailUiEvent.Error(it.message ?: "Save failed")) }
    }

    fun setTags(current: Task, tagIds: List<TagId>) = scope.launch {
        updateTask(current.copy(tags = tagIds))
            .onSuccess { _events.emit(TaskDetailUiEvent.Saved("Tags updated")) }
            .onFailure { _events.emit(TaskDetailUiEvent.Error(it.message ?: "Save failed")) }
    }

    fun removeTag(current: Task, tagId: TagId) = scope.launch {
        val newTags = current.tags - tagId
        updateTask(current.copy(tags = newTags))
            .onSuccess { _events.emit(TaskDetailUiEvent.Saved("Tag removed")) }
            .onFailure { _events.emit(TaskDetailUiEvent.Error(it.message ?: "Save failed")) }
    }

    // ─── Checklist ─────────────────────────────────────────────────────────────

    fun toggleChecklistItem(item: ChecklistItem) = scope.launch {
        checklistUseCase.toggleItem(item.taskId, item.id)
            .onFailure { _events.emit(TaskDetailUiEvent.Error(it.message ?: "Toggle failed")) }
    }

    fun addChecklistItem(taskId: TaskId, title: String) = scope.launch {
        if (title.isBlank()) return@launch
        checklistUseCase.addItem(taskId.value, title.trim())
            .onSuccess { _events.emit(TaskDetailUiEvent.Saved("Item added")) }
            .onFailure { _events.emit(TaskDetailUiEvent.Error(it.message ?: "Add failed")) }
    }

    fun deleteChecklistItem(id: ChecklistItemId) = scope.launch {
        checklistUseCase.deleteItem(id)
            .onFailure { _events.emit(TaskDetailUiEvent.Error(it.message ?: "Delete failed")) }
    }

    // ─── Reminders ──────────────────────────────────────────────────────────────

    fun setReminder(current: Task, offset: ReminderOffset) = scope.launch {
        if (offset == ReminderOffset.AT_DUE) {
            // Remove the reminder entirely
            reminderRepo.deleteByTask(current.id, currentUser.current)
            return@launch
        }
        val now = Clock.now().toEpochMilliseconds()
        val fireAt = computeFireAt(current.dueDate, current.dueTime, offset, now)
        val reminder = Reminder(
            id = ReminderId.generate(),
            taskId = current.id,
            userId = currentUser.current,
            type = ReminderType.Gentle,
            offsetMinutes = -offset.minutes,
            fireAt = fireAt,
            recurringPattern = null,
        )
        reminderRepo.upsert(reminder)
            .onSuccess { _events.emit(TaskDetailUiEvent.Saved("Reminder set")) }
            .onFailure { _events.emit(TaskDetailUiEvent.Error(it.message ?: "Failed to set reminder")) }
    }

    fun deleteReminder(current: Task) = scope.launch {
        reminderRepo.deleteByTask(current.id, currentUser.current)
            .onSuccess { _events.emit(TaskDetailUiEvent.Saved("Reminder removed")) }
            .onFailure { _events.emit(TaskDetailUiEvent.Error(it.message ?: "Failed to remove reminder")) }
    }

    private fun computeFireAt(
        dueDate: kotlinx.datetime.LocalDate?,
        dueTime: String?,
        offset: ReminderOffset,
        nowEpochMs: Long,
    ): Long {
        if (dueDate == null) return nowEpochMs
        val zone = timeZoneProvider.current()
        val hourMinute = dueTime?.split(":")?.map { it.toIntOrNull() }
            ?.takeIf { it.size == 2 && it.all { v -> v != null } }
            ?.let { (h, m) -> h!! to m!! }
        val hour = hourMinute?.first ?: 12
        val minute = hourMinute?.second ?: 0
        val ldt = kotlinx.datetime.LocalDateTime(dueDate.year, dueDate.month, dueDate.day, hour, minute)
        val base = ldt.toInstant(zone).toEpochMilliseconds()
        return base - offset.minutes * 60_000L
    }

    // ─── Delete ────────────────────────────────────────────────────────────────

    fun deleteTask(current: Task) = scope.launch {
        taskRepo.softDelete(current.id)
            .onSuccess {
                _events.emit(TaskDetailUiEvent.Saved("Task deleted"))
                _events.emit(TaskDetailUiEvent.NavigateBack)
            }
            .onFailure { _events.emit(TaskDetailUiEvent.Error(it.message ?: "Delete failed")) }
    }

    // ─── Archive ───────────────────────────────────────────────────────────────

    fun confirmArchive() = scope.launch { _events.emit(TaskDetailUiEvent.ConfirmArchive) }

    fun archiveTask(current: Task) = scope.launch {
        taskRepo.softDelete(current.id)
            .onSuccess {
                _events.emit(TaskDetailUiEvent.Saved("Task archived"))
                _events.emit(TaskDetailUiEvent.NavigateBack)
            }
            .onFailure { _events.emit(TaskDetailUiEvent.Error(it.message ?: "Archive failed")) }
    }

    // ─── Sheet / dialog triggers ────────────────────────────────────────────────

    fun openDatePicker() = scope.launch { _events.emit(TaskDetailUiEvent.OpenDatePicker) }
    fun openTimePicker() = scope.launch { _events.emit(TaskDetailUiEvent.OpenTimePicker) }
    fun openPrioritySheet() = scope.launch { _events.emit(TaskDetailUiEvent.OpenPrioritySheet) }
    fun openProjectSheet() = scope.launch { _events.emit(TaskDetailUiEvent.OpenProjectSheet) }
    fun openTagSheet() = scope.launch { _events.emit(TaskDetailUiEvent.OpenTagSheet) }
    fun openReminderSheet() = scope.launch { _events.emit(TaskDetailUiEvent.OpenReminderSheet) }
    fun openAttachmentSheet() = scope.launch { _events.emit(TaskDetailUiEvent.OpenAttachmentSheet) }
    fun confirmDelete() = scope.launch { _events.emit(TaskDetailUiEvent.ConfirmDelete) }
}
