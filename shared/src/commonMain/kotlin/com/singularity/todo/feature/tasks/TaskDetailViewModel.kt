package com.singularity.todo.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.attachments.Attachment
import com.singularity.todo.core.attachments.AttachmentRepository
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.ui.components.FieldMode
import com.singularity.todo.core.ui.components.UiEvent
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistRepository
import com.singularity.todo.feature.projects.Project
import com.singularity.todo.feature.projects.ProjectsRepository
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.reminders.ReminderRepository
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Combined read model for [TaskDetailScreen]. */
data class TaskDetailUi(
    val task: Task,
    val project: Project? = null,
    val tags: List<Tag> = emptyList(),
    val checklist: List<ChecklistItem> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val titleField: FieldMode = FieldMode.View,
    val descriptionField: FieldMode = FieldMode.View,
    val dueDateField: FieldMode = FieldMode.View,
    val dueTimeField: FieldMode = FieldMode.View,
    val priorityField: FieldMode = FieldMode.View,
    val projectField: FieldMode = FieldMode.View,
)

sealed interface TaskDetailUiState {
    data object Loading : TaskDetailUiState
    data class Loaded(val ui: TaskDetailUi) : TaskDetailUiState
    data class Error(val message: String) : TaskDetailUiState
}

sealed interface TaskDetailField {
    data object Title : TaskDetailField
    data object Description : TaskDetailField
    data object DueDate : TaskDetailField
    data object DueTime : TaskDetailField
    data object Priority : TaskDetailField
    data object Project : TaskDetailField
}

@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailViewModel(
    private val taskRepo: TaskRepository,
    private val updateTask: UpdateTaskUseCase,
    private val projectsRepo: ProjectsRepository,
    private val tagsRepo: TagsRepository,
    private val checklistRepo: ChecklistRepository,
    private val reminderRepo: ReminderRepository,
    private val attachmentsRepo: AttachmentRepository,
    private val currentUser: CurrentUser,
) : ViewModel() {

    private val _taskId = MutableStateFlow<TaskId?>(null)
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    val state: StateFlow<TaskDetailUiState> = _taskId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf<TaskDetailUiState>(TaskDetailUiState.Loading)
            } else {
                val taskFlow = taskRepo.watchTask(id)
                // Project is reactive on the task's projectId; resolves to null if absent.
                val projectFlow = taskFlow.map { task ->
                    val projectId = task?.projectId
                    if (projectId == null) flowOf<Project?>(null)
                    else projectsRepo.watchProject(projectId)
                }.flatMapLatest { it }

                // Tags: load all user tags, filter by task.tags on the consumer side.
                val tagsFlow = tagsRepo.watchTags(currentUser.current.value)
                val checklistFlow = checklistRepo.watchByTask(id.value)
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
                            )
                        )
                    }
                }
            }
        }
        .catch { emit(TaskDetailUiState.Error(it.message ?: "Error")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TaskDetailUiState.Loading)

    fun start(taskId: TaskId) {
        _taskId.value = taskId
    }

    fun saveField(current: Task, field: TaskDetailField, draft: String) = viewModelScope.launch {
        val updated = when (field) {
            TaskDetailField.Title -> current.copy(title = draft)
            TaskDetailField.Description -> current.copy(description = draft.ifBlank { null })
            TaskDetailField.DueDate -> {
                val newDate = draft.takeIf { it.isNotBlank() }
                    ?.let { runCatching { kotlinx.datetime.LocalDate.parse(it) }.getOrNull() }
                current.copy(dueDate = newDate)
            }
            TaskDetailField.DueTime -> current.copy(dueTime = draft.takeIf { it.isNotBlank() })
            TaskDetailField.Priority -> {
                val parsed = runCatching { TaskPriority.valueOf(draft) }.getOrNull() ?: current.priority
                current.copy(priority = parsed)
            }
            TaskDetailField.Project -> current // project editing requires a picker UI
        }
        updateTask(updated)
            .onSuccess { _events.emit(UiEvent.ShowDialog("Saved", "Field updated")) }
            .onFailure { _events.emit(UiEvent.ShowError(it.message ?: "Save failed")) }
    }
}
