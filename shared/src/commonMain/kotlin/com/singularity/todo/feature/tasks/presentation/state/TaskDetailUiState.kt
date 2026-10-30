package com.singularity.todo.feature.tasks.presentation.state
import com.singularity.todo.core.text.OwnedText
import com.singularity.todo.core.text.Revisioned
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tasks.domain.model.BlockedCloseRequest
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.LogbookEntry
import com.singularity.todo.feature.timetracking.domain.model.TaskTimeSlotState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf

/**
 * Read model для экрана просмотра задачи.
 *
 * ## Why the collections are persistent
 *
 * Every collection here is an [ImmutableList] / [ImmutableSet]: the Compose
 * compiler treats those as known-stable, so a composable that takes one of
 * them compares it by `equals` instead of by identity. The Room mapper builds
 * a new instance of each collection on every read, so an identity comparison
 * would recompose the section every time the screen is observed.
 *
 * The screen *state* around this model deliberately keeps no such claim: see
 * [TaskDetailUiState] for why the annotation was removed there.
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
    val tags: ImmutableList<Tag> = persistentListOf(),
    val checklist: ImmutableList<ChecklistItem> = persistentListOf(),
    val reminders: ImmutableList<Reminder> = persistentListOf(),
    /** Direct child tasks (1-level hierarchy only). */
    val subtasks: ImmutableList<Task> = persistentListOf(),
    /** IDs of tasks this task depends on. Used to populate [DependencyPickerSheet]. */
    val blockedBy: ImmutableSet<TaskId> = persistentSetOf(),
    /** IDs of the tasks this task follows up on — the ordering graph, not the blocking one. */
    val followsUp: ImmutableSet<TaskId> = persistentSetOf(),
    /** Tasks available for dependency selection (not trashed, not self). */
    val availableTasks: ImmutableList<Task> = persistentListOf(),
    /** Notes that link TO this task via task:// URL scheme. */
    val linkedNotes: ImmutableList<Note> = persistentListOf(),
    /** Tasks that link TO this task via task:// URL scheme. */
    val linkedTasks: ImmutableList<Task> = persistentListOf(),
    /** Logbook entries (notes + time entries) attached to this task, newest first. */
    val logbookEntries: ImmutableList<LogbookEntry> = persistentListOf(),
    /** Time tracking state for this task. */
    val timeSlotState: TaskTimeSlotState = TaskTimeSlotState.Idle,
    /**
     * The members of the series this task belongs to, in date order.
     *
     * Empty for a task that stands alone — which is most of them. The series
     * card renders from this list and offers the actions that reach all of it.
     */
    val seriesMembers: ImmutableList<Task> = persistentListOf(),
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
 *
 * @property pendingBlockedClose A task whose prerequisites are still open, waiting for the
 *        user to confirm closing it anyway. Null when no confirmation is pending.
 *
 * ## Why there is no `@Immutable` here
 *
 * The annotation made the compiler compare the screen state by `equals` — a
 * deep walk over the task's tags, checklist, subtasks and logbook, on every
 * comparison. A screen state is compared by identity: that is O(1), and the
 * screen body is cheap to re-run because the *leaves* inside it are stable
 * and skip themselves. The annotation was a claim about the whole screen that
 * nobody had measured; the leaves carry the stability instead.
 */
sealed interface TaskDetailUiState {
    data object Loading : TaskDetailUiState
    data class Error(val message: String) : TaskDetailUiState
    data class Loaded(
        val ui: TaskDetailUi,
        val extras: TaskDetailExtras,
        val pendingBlockedClose: BlockedCloseRequest? = null,
    ) : TaskDetailUiState
}
