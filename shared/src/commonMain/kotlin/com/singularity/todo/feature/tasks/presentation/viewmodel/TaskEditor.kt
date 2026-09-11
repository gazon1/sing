package com.singularity.todo.feature.tasks.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderType
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.feature.tasks.domain.model.ChecklistItemUi
import com.singularity.todo.feature.tasks.domain.model.CreateTaskInput
import com.singularity.todo.feature.tasks.domain.model.TaskEditorDeps
import com.singularity.todo.feature.tasks.domain.model.TaskEditorIntent
import com.singularity.todo.feature.tasks.domain.model.TaskEditorMode
import com.singularity.todo.feature.tasks.domain.model.TaskEditorUiEvent
import com.singularity.todo.feature.tasks.domain.model.TaskEditorUiState
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.reduce
import com.singularity.todo.core.ids.UserId
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

        val input: Either<AppError.Validation, CreateTaskInput> = com.singularity.todo.feature.tasks.domain.TaskDomain.createInput(
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
                _uiState.update { it -> it.copy(saving = false) }
                _events.emit(TaskEditorUiEvent.Error(it.message ?: "Failed to save"))
            }
    }

    private suspend fun saveSubEntities(taskId: TaskId, current: TaskEditorUiState, userId: UserId) {
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

        if (current.tagIds.isNotEmpty()) {
            val tagIds = current.tagIds.map { com.singularity.todo.feature.tags.TagId.fromString(it) }
            deps.taskRepository.setTags(taskId, tagIds)
        }

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
            deps.reminderRepository.upsert(reminder).onFailure { }
        }

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
                        dueTime = task.dueTime?.let { it -> parseTime(it) },
                        projectId = task.projectId?.value,
                        tagIds = task.tags.map { it -> it.value },
                        checklistItems = checklist.map { item ->
                            ChecklistItemUi(
                                id = item.id.value,
                                title = item.title,
                                isCompleted = item.isCompleted,
                            )
                        },
                        reminderOffset = reminder?.let { r ->
                            val abs = (-r.offsetMinutes).coerceAtLeast(0)
                            ReminderOffset.entries.find { it -> it.minutes == abs }
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
                dueTime = original.dueTime?.let { it -> parseTime(it) },
                projectId = original.projectId?.value,
                tagIds = original.tags.map { it -> it.value },
                checklistItems = emptyList(),
                reminderOffset = null,
                pendingAttachments = emptyList(),
            )
        }
    }

    private fun parseTime(hhmm: String): kotlinx.datetime.LocalTime? {
        val parts = hhmm.split(":")
        return if (parts.size == 2) {
            kotlinx.datetime.LocalTime(parts[0].toIntOrNull() ?: return null, parts[1].toIntOrNull() ?: return null)
        } else null
    }
}
