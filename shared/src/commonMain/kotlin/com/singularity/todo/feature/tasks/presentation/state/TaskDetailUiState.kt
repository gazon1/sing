package com.singularity.todo.feature.tasks.presentation.state
import androidx.compose.runtime.Immutable
import com.singularity.todo.core.attachments.Attachment
import com.singularity.todo.core.ui.textfields.OwnedText
import com.singularity.todo.core.ui.textfields.Revisioned
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
    /**
     * The draft title owned by VM — prevents mirror-state in Composable.
     *
     * It carries the count of the times the task was rewritten from outside, and that
     * is what tells the bridged text fields that the new text is a *replacement* rather
     * than the echo of a keystroke the field itself produced. The count never moves for
     * a keystroke.
     */
    val titleDraft: Revisioned<String> = Revisioned.initial(task.title),
    /** Draft description owned by VM — prevents mirror-state in Composable. */
    val descriptionDraft: Revisioned<String> = Revisioned.initial(task.description ?: ""),
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
) {
    /**
     * The title as the field's source.
     *
     * The coordinator observes the task, so the task can be rewritten from outside the
     * screen — a sync from another device, a change made on another one — and the
     * rewrite can land on a title the field itself produced. The draft counts the
     * replacements, and the type says the count is there: a field that holds this
     * source cannot be wired as if the title were a value nobody else writes.
     */
    val titleSource: OwnedText get() = OwnedText(titleDraft.value, titleDraft.revision)

    /**
     * The description as the field's source; see [titleSource] for why the count is
     * there.
     */
    val descriptionSource: OwnedText get() =
        OwnedText(descriptionDraft.value, descriptionDraft.revision)
}

/**
 * UI state экрана просмотра задачи.
 * [Loading] и [Error] — терминальные; [Loaded] — основной.
 */
@Immutable
sealed interface TaskDetailUiState {
    data object Loading : TaskDetailUiState
    data class Error(val message: String) : TaskDetailUiState
    data class Loaded(val ui: TaskDetailUi, val extras: TaskDetailExtras) : TaskDetailUiState
}
