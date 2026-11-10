package com.singularity.todo.feature.agenda.presentation.state
import com.singularity.todo.core.ui.components.formatRussianDueDateOrNull
import com.singularity.todo.feature.agenda.domain.model.AgendaRowItem
import com.singularity.todo.feature.agenda.domain.model.RenderedSection
import com.singularity.todo.feature.tasks.domain.model.BlockedBulkCloseRequest
import com.singularity.todo.feature.tasks.domain.model.BlockedCloseRequest
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.model.TaskUi
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.datetime.LocalDate

/**
 * UI state for the Agenda screen.
 *
 * @param sections The evaluated and rendered sections with their tasks.
 * @param today The current date at the time of evaluation, used to label relative buckets.
 * @param isSelectionMode true when the user is in multi-selection mode.
 * @param selectedTaskIds The set of task IDs currently selected.
 * @param pendingBlockedClose A task whose prerequisites are still open, waiting for the
 *        user to confirm closing it anyway. Null when no confirmation is pending.
 * @param pendingBlockedBulkClose A selection in which some tasks have open prerequisites,
 *        waiting for the user to confirm closing the selection anyway. Null when no
 *        confirmation is pending.
 *
 * ## Why there is no `@Immutable` here
 *
 * The state of a whole screen is compared by identity, and that is the cheap
 * and honest thing to do: an `equals` over every section of the agenda would
 * walk every task in it, on every recomposition of the screen.
 *
 * The class used to carry `@Immutable`, which made the compiler compare it by
 * `equals` — the deep comparison the annotation's name suggests and the one
 * nobody wants at screen level. The screen is cheap to re-run and expensive to
 * compare; the *leaves* are the other way round, and they are what carry the
 * stability. See docs/architecture/compose-stability.md.
 */
sealed interface AgendaUiState {
    data object Loading : AgendaUiState

    data class Loaded(
        val sections: ImmutableList<RenderedSection>,
        val today: LocalDate,
        val greeting: String = "",
        val isSelectionMode: Boolean = false,
        val selectedTaskIds: ImmutableSet<TaskId> = persistentSetOf(),
        val pendingBlockedClose: BlockedCloseRequest? = null,
        val pendingBlockedBulkClose: BlockedBulkCloseRequest? = null,
    ) : AgendaUiState

    data class Error(val message: String) : AgendaUiState
}

fun AgendaRowItem.toTaskUi(isSelected: Boolean): TaskUi =
    TaskUi(
        id = task.id,
        title = task.title,
        project = project,
        projectColor = projectColor,
        dueLabel = formatRussianDueDateOrNull(task.dueDate),
        parentId = task.parentTaskId?.value,
        indentLevel = if (task.parentTaskId != null) 1 else 0,
        isPinned = task.isPinned,
        priority = task.priority,
        isCompleted = task.completedAt != null,
        isSelected = isSelected,
        blockedBy = task.blockedBy.toImmutableSet(),
        markings = markings,
        seriesDates = seriesDates,
    )
