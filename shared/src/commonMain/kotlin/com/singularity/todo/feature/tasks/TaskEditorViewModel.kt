package com.singularity.todo.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.checklist.ChecklistUseCase
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderRepository
import com.singularity.todo.feature.reminders.ReminderType
import com.singularity.todo.feature.settings.ReminderOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant

/**
 * Whether the editor is creating a new task or editing an existing one.
 */
sealed interface TaskEditorMode {
    data object New : TaskEditorMode
    data class Edit(val taskId: TaskId) : TaskEditorMode
}

/**
 * UI state for the create/edit task screen.
 *
 * The screen is stateless — every field belongs to the VM so it can be tested
 * without Compose and so save/error handling lives in one place.
 *
 * @param originalTask When non-null, the editor is in edit mode with this task pre-loaded.
 *                     Used for dirty tracking and discard.
 */
data class TaskEditorUiState(
    val mode: TaskEditorMode = TaskEditorMode.New,
    val title: String = "",
    val description: String = "",
    val priority: TaskPriority = TaskPriority.None,
    val dueDate: kotlinx.datetime.LocalDate? = null,
    val dueTime: kotlinx.datetime.LocalTime? = null,
    val projectId: String? = null,
    val tagIds: List<String> = emptyList(),
    val checklistItems: List<ChecklistItemUi> = emptyList(),
    val newChecklistItem: String = "",
    val reminderOffset: ReminderOffset? = null,
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val errorMessage: String? = null,
    /** Pre-loaded task for edit mode; used for dirty comparison and discard. */
    val originalTask: Task? = null,
) {
    /**
     * Detects unsaved changes by comparing against the pre-loaded [originalTask].
     * In New mode, dirty when any field has a non-default value.
     */
    val dirty: Boolean
        get() = when (val o = originalTask) {
            null -> title.isNotBlank() || description.isNotBlank() ||
                priority != TaskPriority.None || dueDate != null || dueTime != null ||
                projectId != null || tagIds.isNotEmpty() ||
                checklistItems.isNotEmpty() || reminderOffset != null ||
                pendingAttachments.isNotEmpty()
            else -> title != o.title || description != (o.description ?: "") ||
                priority != o.priority ||
                dueDate != o.dueDate ||
                dueTime != o.dueTime?.let { parseTime(it) } ||
                projectId != o.projectId?.value ||
                tagIds != o.tags.map { it.value }
        }
}

data class PendingAttachment(
    val path: String,
    val name: String,
    val mimeType: String?,
)

data class ChecklistItemUi(
    val id: String,
    val title: String,
    val isCompleted: Boolean = false,
)

sealed interface TaskEditorIntent {
    data class TitleChanged(val text: String) : TaskEditorIntent
    data class DescriptionChanged(val text: String) : TaskEditorIntent
    data class PriorityChanged(val priority: TaskPriority) : TaskEditorIntent
    data class DueDateChanged(val date: kotlinx.datetime.LocalDate?) : TaskEditorIntent
    data object ClearDueDate : TaskEditorIntent
    data class DueTimeChanged(val time: kotlinx.datetime.LocalTime?) : TaskEditorIntent
    data object ClearDueTime : TaskEditorIntent
    data class ProjectChanged(val projectId: String?) : TaskEditorIntent
    data class TagsChanged(val tagIds: List<String>) : TaskEditorIntent
    data class NewChecklistItemChanged(val text: String) : TaskEditorIntent
    data object AddChecklistItem : TaskEditorIntent
    data class ToggleChecklistItem(val id: String) : TaskEditorIntent
    data class DeleteChecklistItem(val id: String) : TaskEditorIntent
    data class ReminderOffsetChanged(val offset: ReminderOffset?) : TaskEditorIntent
    data class AddAttachment(val path: String, val name: String, val mimeType: String?) : TaskEditorIntent
    data class RemovePendingAttachment(val path: String) : TaskEditorIntent
    /** Loads an existing task for editing; switches mode to Edit. */
    data class LoadTask(val taskId: TaskId) : TaskEditorIntent
    /** Resets state to the original loaded task (discards changes). */
    data object DiscardChanges : TaskEditorIntent
    data object Save : TaskEditorIntent
    data object ErrorShown : TaskEditorIntent
}

/**
 * Pure reducer — `(State, Intent) -> State`. Covers all intents that don't
 * have IO / list-mutation side effects. Tested without a VM in
 * [TaskEditorReducerTest].
 */
internal fun TaskEditorUiState.reduce(intent: TaskEditorIntent): TaskEditorUiState = when (intent) {
    is TaskEditorIntent.TitleChanged -> copy(title = intent.text, errorMessage = null)
    is TaskEditorIntent.DescriptionChanged -> copy(description = intent.text)
    is TaskEditorIntent.PriorityChanged -> copy(priority = intent.priority)
    is TaskEditorIntent.DueDateChanged -> copy(dueDate = intent.date)
    TaskEditorIntent.ClearDueDate -> copy(dueDate = null)
    is TaskEditorIntent.DueTimeChanged -> copy(dueTime = intent.time)
    TaskEditorIntent.ClearDueTime -> copy(dueTime = null)
    is TaskEditorIntent.ProjectChanged -> copy(projectId = intent.projectId)
    is TaskEditorIntent.TagsChanged -> copy(tagIds = intent.tagIds)
    is TaskEditorIntent.NewChecklistItemChanged -> copy(newChecklistItem = intent.text)
    is TaskEditorIntent.ReminderOffsetChanged -> copy(reminderOffset = intent.offset)
    is TaskEditorIntent.AddAttachment -> copy(
        pendingAttachments = pendingAttachments + PendingAttachment(
            path = intent.path,
            name = intent.name,
            mimeType = intent.mimeType,
        ),
    )
    is TaskEditorIntent.RemovePendingAttachment -> copy(
        pendingAttachments = pendingAttachments.filter { it.path != intent.path },
    )
    TaskEditorIntent.ErrorShown -> copy(errorMessage = null)
    // Impure intents pass through unchanged — handled separately in the VM.
    TaskEditorIntent.AddChecklistItem,
    is TaskEditorIntent.ToggleChecklistItem,
    is TaskEditorIntent.DeleteChecklistItem,
    TaskEditorIntent.Save,
    is TaskEditorIntent.LoadTask,
    TaskEditorIntent.DiscardChanges -> this
}

/**
 * Dependencies for [TaskEditorViewModel] — reduces constructor parameter count
 * and makes DI registration more maintainable.
 */
data class TaskEditorDeps(
    val createTask: CreateTaskUseCase,
    val updateTask: UpdateTaskUseCase,
    val clock: Clock,
    val currentUser: ProfileAwareCurrentUser,
    val taskRepository: TaskRepository,
    val checklistUseCase: ChecklistUseCase,
    val reminderRepository: ReminderRepository,
    val attachmentSaver: AttachmentSaver,
    val idGen: IdGenerator,
    val timeZoneProvider: TimeZoneProvider,
)

/**
 * Create/edit task screen VM.
 *
 * @param initialDueDate pre-fills the due date field (e.g. when creating from Today tab).
 */
class TaskEditorViewModel(
    private val deps: TaskEditorDeps,
    initialDueDate: kotlinx.datetime.LocalDate? = null,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    private val _uiState = MutableStateFlow(TaskEditorUiState(dueDate = initialDueDate))
    val uiState: StateFlow<TaskEditorUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<TaskEditorUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<TaskEditorUiEvent> = _events.asSharedFlow()

    fun onIntent(intent: TaskEditorIntent) {
        _uiState.update { it.reduce(intent) }
        when (intent) {
            TaskEditorIntent.AddChecklistItem -> addChecklistItem()
            is TaskEditorIntent.ToggleChecklistItem -> toggleChecklistItem(intent.id)
            is TaskEditorIntent.DeleteChecklistItem -> deleteChecklistItem(intent.id)
            TaskEditorIntent.Save -> save()
            is TaskEditorIntent.LoadTask -> loadTask(intent.taskId)
            TaskEditorIntent.DiscardChanges -> discardChanges()
            else -> Unit
        }
    }

    private fun addChecklistItem() {
        val text = _uiState.value.newChecklistItem.trim()
        if (text.isBlank()) return
        _uiState.update { st ->
            st.copy(
                checklistItems = st.checklistItems + ChecklistItemUi(
                    id = deps.idGen.next(),
                    title = text,
                    isCompleted = false,
                ),
                newChecklistItem = "",
            )
        }
    }

    private fun toggleChecklistItem(id: String) {
        _uiState.update { st ->
            st.copy(
                checklistItems = st.checklistItems.map { item ->
                    if (item.id == id) item.copy(isCompleted = !item.isCompleted) else item
                },
            )
        }
    }

    private fun deleteChecklistItem(id: String) {
        _uiState.update { st ->
            st.copy(checklistItems = st.checklistItems.filter { it.id != id })
        }
    }

    private fun computeFireAt(
        dueDate: kotlinx.datetime.LocalDate?,
        dueTime: kotlinx.datetime.LocalTime?,
        offset: ReminderOffset,
        nowEpochMs: Long,
    ): Long {
        val zone = deps.timeZoneProvider.current()
        val base = when {
            dueDate != null && dueTime != null -> {
                val ldt = LocalDateTime(dueDate.year, dueDate.month, dueDate.day, dueTime.hour, dueTime.minute)
                ldt.toInstant(zone).toEpochMilliseconds()
            }
            dueDate != null -> {
                val ldt = LocalDateTime(dueDate.year, dueDate.month, dueDate.day, 12, 0)
                ldt.toInstant(zone).toEpochMilliseconds()
            }
            else -> nowEpochMs
        }
        return base - offset.minutes * 60_000L
    }

    private fun save() = scope.launch(Dispatchers.Unconfined) {
        val current = _uiState.value
        if (current.saving) return@launch

        val userId = deps.currentUser.current

        _uiState.update { it.copy(saving = true, errorMessage = null) }

        when (current.mode) {
            TaskEditorMode.New -> saveNew(current, userId)
            is TaskEditorMode.Edit -> saveExisting(current, current.mode.taskId, userId)
        }
    }

    private suspend fun saveNew(current: TaskEditorUiState, userId: UserId) {
        _uiState.update { it.copy(saving = true, errorMessage = null) }

        val input: Either<AppError.Validation, CreateTaskInput> = TasksDomain.createInput(
            title = current.title,
            description = current.description.ifBlank { null },
            priority = current.priority,
            projectId = current.projectId?.let { com.singularity.todo.feature.projects.ProjectId.fromString(it) },
            tagIds = current.tagIds.map { com.singularity.todo.feature.tags.TagId.fromString(it) },
            dueDate = current.dueDate,
            dueTime = current.dueTime?.let { "%02d:%02d".format(it.hour, it.minute) },
            userId = userId,
        )
        if (input is Either.Left) {
            _uiState.update { it.copy(saving = false, errorMessage = input.error.message) }
            return
        }

        deps.createTask((input as Either.Right).value)
            .onSuccess { taskId ->
                saveSubEntities(taskId, current, userId)
                _events.emit(TaskEditorUiEvent.NavigateBack)
            }
            .onFailure { it ->
                _uiState.update { it.copy(saving = false) }
                _events.emit(TaskEditorUiEvent.Error(it.message ?: "Failed to save"))
            }
    }

    private suspend fun saveExisting(current: TaskEditorUiState, taskId: TaskId, userId: UserId) {
        val existing = deps.taskRepository.watchTask(taskId).first()
            ?: run {
                _uiState.update { it.copy(saving = false) }
                _events.emit(TaskEditorUiEvent.Error("Task not found"))
                return
            }

        val updated = existing.copy(
            title = current.title,
            description = current.description.ifBlank { null },
            priority = current.priority,
            projectId = current.projectId?.let { com.singularity.todo.feature.projects.ProjectId.fromString(it) },
            dueDate = current.dueDate,
            dueTime = current.dueTime?.let { "%02d:%02d".format(it.hour, it.minute) },
        )

        deps.updateTask(updated)
            .onSuccess {
                saveSubEntities(taskId, current, userId)
                _events.emit(TaskEditorUiEvent.NavigateBack)
            }
            .onFailure {
                _uiState.update { it.copy(saving = false) }
                _events.emit(TaskEditorUiEvent.Error(it.message ?: "Failed to save"))
            }
    }

    private suspend fun saveSubEntities(taskId: TaskId, current: TaskEditorUiState, userId: UserId) {
        // Checklist
        if (current.checklistItems.isNotEmpty()) {
            val items = current.checklistItems.map { ui ->
                ChecklistItem(
                    id = ChecklistItemId.fromString(ui.id),
                    taskId = taskId.value,
                    title = ui.title,
                    isCompleted = ui.isCompleted,
                    sortOrder = 0,
                )
            }
            deps.checklistUseCase.createBatch(taskId.value, items)
        }

        // Tags — fix: tagIds were stored but never persisted
        if (current.tagIds.isNotEmpty()) {
            val tagIds = current.tagIds.map { com.singularity.todo.feature.tags.TagId.fromString(it) }
            deps.taskRepository.setTags(taskId, tagIds)
        }

        // Reminder
        current.reminderOffset?.let { offset ->
            val now = deps.clock.now().toEpochMilliseconds()
            val fireAt = computeFireAt(current.dueDate, current.dueTime, offset, now)
            val reminder = Reminder(
                id = ReminderId.generate(),
                taskId = taskId,
                userId = userId,
                type = ReminderType.Gentle,
                offsetMinutes = -offset.minutes,
                fireAt = fireAt,
                recurringPattern = null,
            )
            deps.reminderRepository.upsert(reminder).onFailure {
                // Best-effort: reminder is non-critical; task is already saved
            }
        }

        // Attachments
        current.pendingAttachments.forEach { att ->
            deps.attachmentSaver.save(taskId, att.path, att.mimeType)
        }
    }

    private fun loadTask(taskId: TaskId) {
        scope.launch(Dispatchers.Unconfined) {
            _uiState.update { it.copy(loading = true) }

            val userId = deps.currentUser.current
            val task = deps.taskRepository.watchTask(taskId).first()

            if (task != null) {
                val checklist = deps.checklistUseCase.watchChecklist(taskId.value).first()
                val reminder = deps.reminderRepository.watchByTask(taskId, userId).first().firstOrNull()

                _uiState.update {
                    it.copy(
                        mode = TaskEditorMode.Edit(taskId),
                        title = task.title,
                        description = task.description ?: "",
                        priority = task.priority,
                        dueDate = task.dueDate,
                        dueTime = task.dueTime?.let { parseTime(it) },
                        projectId = task.projectId?.value,
                        tagIds = task.tags.map { it.value },
                        checklistItems = checklist.map { item ->
                            ChecklistItemUi(
                                id = item.id.value,
                                title = item.title,
                                isCompleted = item.isCompleted,
                            )
                        },
                        reminderOffset = reminder?.let { r ->
                            val abs = (-r.offsetMinutes).coerceAtLeast(0)
                            ReminderOffset.entries.find { it.minutes == abs }
                        },
                        originalTask = task,
                        loading = false,
                    )
                }
            } else {
                _uiState.update { it.copy(loading = false) }
                _events.emit(TaskEditorUiEvent.Error("Task not found"))
            }
        }
    }

    private fun discardChanges() {
        val original = _uiState.value.originalTask ?: return
        _uiState.update {
            it.copy(
                title = original.title,
                description = original.description ?: "",
                priority = original.priority,
                dueDate = original.dueDate,
                dueTime = original.dueTime?.let { parseTime(it) },
                projectId = original.projectId?.value,
                tagIds = original.tags.map { it.value },
                checklistItems = emptyList(),
                reminderOffset = null,
                pendingAttachments = emptyList(),
            )
        }
    }

}

private fun parseTime(hhmm: String): kotlinx.datetime.LocalTime? {
    val parts = hhmm.split(":")
    return if (parts.size == 2) {
        kotlinx.datetime.LocalTime(parts[0].toIntOrNull() ?: return null, parts[1].toIntOrNull() ?: return null)
    } else null
}
