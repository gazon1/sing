package com.singularity.todo.feature.tasks.presentation.state
import androidx.compose.runtime.Immutable

import com.singularity.todo.core.attachments.Attachment
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.LogbookEntry
import com.singularity.todo.feature.timetracking.domain.model.TaskTimeSlotState

/**
 * Read model для экрана просмотра задачи.
 */
data class TaskDetailUi(
    val task: Task,
    /** Draft title owned by VM — prevents mirror-state in Composable. */
    val titleDraft: String = task.title,
    /** Draft description owned by VM — prevents mirror-state in Composable. */
    val descriptionDraft: String = task.description ?: "",
    val project: Project? = null,
    val tags: List<Tag> = emptyList(),
    val checklist: List<ChecklistItem> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    /** Direct child tasks (1-level hierarchy only). */
    val subtasks: List<Task> = emptyList(),
    /** IDs of tasks this task depends on. Used to populate [DependencyPickerSheet]. */
    val dependsOn: Set<TaskId> = emptySet(),
    /** Tasks available for dependency selection (not trashed, not self). */
    val availableTasks: List<Task> = emptyList(),
    /** Notes that link TO this task via task:// URL scheme. */
    val linkedNotes: List<Note> = emptyList(),
    /** Tasks that link TO this task via task:// URL scheme. */
    val linkedTasks: List<Task> = emptyList(),
    /** Logbook entries (notes + time entries) attached to this task, newest first. */
    val logbookEntries: List<LogbookEntry> = emptyList(),
    /** Time tracking state for this task. */
    val timeSlotState: TaskTimeSlotState = TaskTimeSlotState.Idle,
    /** First-run state for this task. */
    val firstRun: FirstRun = FirstRun.Unresolved,
)

/**
 * UI state экрана просмотра задачи.
 * [Loading] и [Error] — терминальные; [Loaded] — основной.
 */
sealed interface TaskDetailUiState {
    data object Loading : TaskDetailUiState
    data class Error(val message: String) : TaskDetailUiState
    data class Loaded(val ui: TaskDetailUi, val extras: TaskDetailExtras) : TaskDetailUiState
}
