package com.singularity.todo.feature.agenda.domain.model

import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * User intents (events) on the Agenda screen.
 */
sealed interface AgendaIntent : MviIntent {
    data class TaskClicked(val taskId: TaskId) : AgendaIntent
    data class TaskCheckClicked(val taskId: TaskId) : AgendaIntent
    data class TaskLongClicked(val taskId: TaskId) : AgendaIntent
    data class TaskPinClicked(val taskId: TaskId) : AgendaIntent
    data class TaskDeleteClicked(val taskId: TaskId) : AgendaIntent
    data class TaskExpandClicked(val taskId: TaskId) : AgendaIntent

    /**
     * The user confirmed closing a task whose prerequisites are still open.
     *
     * Nothing was written when the confirmation was raised — the close happens
     * here, and only here.
     */
    data class ConfirmBlockedClose(val taskId: TaskId) : AgendaIntent

    /**
     * The user backed out of closing a task whose prerequisites are still open.
     *
     * Nothing was written, so this only closes the confirmation.
     */
    data object DismissBlockedClose : AgendaIntent

    /**
     * The user confirmed closing a selection in which some tasks have open
     * prerequisites.
     *
     * The same question the single close asks, asked about a selection. Nothing
     * was written when the confirmation was raised — the close happens here, and
     * only here.
     */
    data object ConfirmBlockedBulkClose : AgendaIntent

    /**
     * The user backed out of closing a selection in which some tasks have open
     * prerequisites.
     *
     * Nothing was written, so this only closes the confirmation — and the
     * selection stays where it was, so the user can pick another action.
     */
    data object DismissBlockedBulkClose : AgendaIntent

    /**
     * The user took the Undo affordance offered after a delete.
     *
     * This used to be a public `onUndoDeleteIntent()` method the screen called
     * directly, which meant no intent handler existed for it and every test that
     * drives the dispatcher skipped the path entirely.
     */
    data object UndoDeleteTapped : AgendaIntent

    /** User tapped '+' in a section header to create a task pre-filled from that section. */
    data class CreateInSection(val sectionId: String) : AgendaIntent

    /**
     * The user picked several days and asked for one task on all of them.
     *
     * The days are concrete: they came out of a grid, not out of "today" or
     * "tomorrow". The task itself is not created here — the days go into a
     * draft, and the editor creates the task, so that a task on several days is
     * created by the same code whichever screen asked for it.
     */
    data class CreateOnDates(val dates: Set<kotlinx.datetime.LocalDate>) : AgendaIntent

    // ── Selection mode ──────────────────────────────────────────────────────────

    /**
     * Long-press on a task enters multi-selection mode, selecting that task.
     * On touch: long-press. On desktop: right-click → "Select" (future).
     */
    data class EnterSelectionMode(val taskId: TaskId) : AgendaIntent

    /**
     * Click on a task while in selection mode toggles its selected state.
     * If it was the last selected task, exits selection mode.
     */
    data class ToggleSelection(val taskId: TaskId) : AgendaIntent

    /** Exit selection mode, clearing all selected tasks. */
    data object ExitSelectionMode : AgendaIntent

    /** Delete all selected tasks via [com.singularity.todo.feature.tasks.domain.usecase.TaskMutationsUseCase.bulkDelete]. */
    data object DeleteSelected : AgendaIntent

    /** Complete all selected tasks via [com.singularity.todo.feature.tasks.domain.usecase.TaskMutationsUseCase.bulkComplete]. */
    data object CompleteSelected : AgendaIntent
}
