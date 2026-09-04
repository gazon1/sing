package com.singularity.todo.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.ui.components.UiEvent
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.checklist.ChecklistUseCase
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderRepository
import com.singularity.todo.feature.reminders.ReminderType
import com.singularity.todo.feature.settings.ReminderOffset
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the create-task screen.
 *
 * The screen is stateless — every field belongs to the VM so it can be tested
 * without Compose and so save/error handling lives in one place.
 */
data class TaskEditorUiState(
    val title: String = "",
    val description: String = "",
    val dueDate: kotlinx.datetime.LocalDate? = null,
    val dueTime: kotlinx.datetime.LocalTime? = null,
    val projectId: String? = null,
    val tagIds: List<String> = emptyList(),
    val checklistItems: List<ChecklistItemUi> = emptyList(),
    val newChecklistItem: String = "",
    val reminderOffset: ReminderOffset? = null,
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val saving: Boolean = false,
    val errorMessage: String? = null,
)

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
    data class DueDateChanged(val date: kotlinx.datetime.LocalDate?) : TaskEditorIntent
    data class DueTimeChanged(val time: kotlinx.datetime.LocalTime?) : TaskEditorIntent
    data class ProjectChanged(val projectId: String?) : TaskEditorIntent
    data class TagsChanged(val tagIds: List<String>) : TaskEditorIntent
    data class NewChecklistItemChanged(val text: String) : TaskEditorIntent
    data object AddChecklistItem : TaskEditorIntent
    data class ToggleChecklistItem(val id: String) : TaskEditorIntent
    data class DeleteChecklistItem(val id: String) : TaskEditorIntent
    data class ReminderOffsetChanged(val offset: ReminderOffset?) : TaskEditorIntent
    data class AddAttachment(val path: String, val name: String, val mimeType: String?) : TaskEditorIntent
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
    is TaskEditorIntent.DueDateChanged -> copy(dueDate = intent.date)
    is TaskEditorIntent.DueTimeChanged -> copy(dueTime = intent.time)
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
    TaskEditorIntent.ErrorShown -> copy(errorMessage = null)
    // Impure intents pass through unchanged — handled separately in the VM.
    TaskEditorIntent.AddChecklistItem,
    is TaskEditorIntent.ToggleChecklistItem,
    is TaskEditorIntent.DeleteChecklistItem,
    TaskEditorIntent.Save -> this
}

/**
 * Create-task screen VM. The legacy implementation lived entirely inside the
 * Composable (`var title by remember { ... }`, `rememberCoroutineScope`); this
 * refactor moves the state and validation into the VM so the screen is a thin
 * view and `TaskEditorViewModelTest` can exercise the validation path.
 *
 * @param initialDueDate pre-fills the due date field (e.g. when creating from Today tab).
 */
class TaskEditorViewModel(
    private val createTask: CreateTaskUseCase,
    private val clock: Clock,
    private val currentUser: CurrentUser,
    private val checklistUseCase: com.singularity.todo.feature.checklist.ChecklistUseCase,
    private val reminderRepository: ReminderRepository,
    private val saveAttachment: (taskId: TaskId, path: String, mimeType: String?) -> Unit,
    initialDueDate: kotlinx.datetime.LocalDate? = null,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    private val _uiState = MutableStateFlow(TaskEditorUiState(dueDate = initialDueDate))
    val uiState: StateFlow<TaskEditorUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    fun onIntent(intent: TaskEditorIntent) {
        // Pure reducer first — covers all intents that don't have side effects.
        _uiState.update { it.reduce(intent) }
        // Impure branch — handles side effects (IO, list mutations, save).
        when (intent) {
            TaskEditorIntent.AddChecklistItem -> addChecklistItem()
            is TaskEditorIntent.ToggleChecklistItem -> toggleChecklistItem(intent.id)
            is TaskEditorIntent.DeleteChecklistItem -> deleteChecklistItem(intent.id)
            TaskEditorIntent.Save -> save()
            else -> Unit
        }
    }

    private fun addChecklistItem() {
        val text = _uiState.value.newChecklistItem.trim()
        if (text.isBlank()) return
        _uiState.update { st ->
            st.copy(
                checklistItems = st.checklistItems + ChecklistItemUi(
                    id = java.util.UUID.randomUUID().toString(),
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
                }
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
        val zone = TimeZone.currentSystemDefault()
        val base = when {
            dueDate != null && dueTime != null -> {
                val ldt = LocalDateTime(dueDate.year, dueDate.monthNumber, dueDate.dayOfMonth, dueTime.hour, dueTime.minute)
                ldt.toInstant(zone).toEpochMilliseconds()
            }
            dueDate != null -> {
                val ldt = LocalDateTime(dueDate.year, dueDate.monthNumber, dueDate.dayOfMonth, 12, 0)
                ldt.toInstant(zone).toEpochMilliseconds()
            }
            else -> nowEpochMs
        }
        return base - offset.minutes * 60_000L
    }

    private fun save() = scope.launch(Dispatchers.Unconfined) {
        val current = _uiState.value
        if (current.saving) return@launch

        val userId = currentUser.current

        val input = try {
            TasksDomain.createInput(
                title = current.title,
                description = current.description.ifBlank { null },
                dueDate = current.dueDate,
                userId = userId,
            )
        } catch (e: AppError.Validation) {
            _uiState.update { it.copy(errorMessage = e.message) }
            return@launch
        }

        _uiState.update { it.copy(saving = true, errorMessage = null) }

        val taskResult = createTask(input)

        taskResult
            .onSuccess { taskId ->
                // Save checklist items after task creation
                if (current.checklistItems.isNotEmpty()) {
                    val checklistItems = current.checklistItems.map { ui ->
                        com.singularity.todo.feature.checklist.ChecklistItem(
                            id = com.singularity.todo.feature.checklist.ChecklistItemId.fromString(ui.id),
                            taskId = taskId.value,
                            title = ui.title,
                            isCompleted = ui.isCompleted,
                            sortOrder = 0,
                        )
                    }
                    checklistUseCase.createBatch(taskId.value, checklistItems)
                }
                // Save reminder if offset was selected
                current.reminderOffset?.let { offset ->
                    val now = clock.now().toEpochMilliseconds()
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
                    reminderRepository.upsert(reminder)
                }
                // Save pending attachments
                current.pendingAttachments.forEach { att ->
                    saveAttachment(taskId, att.path, att.mimeType)
                }
                _events.emit(UiEvent.NavigateBack)
            }
            .onFailure {
                _uiState.update { it -> it.copy(saving = false) }
                _events.emit(UiEvent.ShowError(it.message ?: "Failed to save"))
            }
    }
}
