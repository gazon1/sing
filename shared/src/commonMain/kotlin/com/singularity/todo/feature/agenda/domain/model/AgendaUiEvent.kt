package com.singularity.todo.feature.agenda.domain.model

import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * One-shot UI events emitted by the Agenda screen.
 * Collected via [kotlinx.coroutines.flow.SharedFlow] in the composable.
 */
sealed interface AgendaUiEvent : MviEvent {
    data class NavigateToTask(val taskId: TaskId) : AgendaUiEvent
    data class ShowTaskContextMenu(val taskId: TaskId) : AgendaUiEvent
    data class ExpandTask(val taskId: TaskId) : AgendaUiEvent

    /**
     * A mutation the user asked for did not happen, and they are told so.
     *
     * The agenda's mutations used to report to the crash reporter and stop there:
     * the user tapped the delete affordance, saw a snackbar announcing a delete
     * that had never been issued, and had no way to learn that. An event type with
     * nowhere to go is why that was possible — see #132.
     */
    data class ShowError(val message: String) : AgendaUiEvent

    /**
     * Navigate to the task create screen with pre-fill from [sectionId].
     * The prefill draft was already written to [com.singularity.todo.core.draft.DraftStore]
     * before this event is emitted.
     */
    data class CreateInSection(val sectionId: String) : AgendaUiEvent

    /**
     * Soft-delete with undo: the task is deleted but a snackbar is shown for 5 seconds.
     * If the user taps Undo within that window, the task is restored.
     *
     * @param taskId The deleted task id (for undo).
     * @param taskTitle Short label for the snackbar.
     */
    data class UndoDelete(val taskId: TaskId, val taskTitle: String) : AgendaUiEvent

    /**
     * A bulk operation (delete or complete) finished.
     *
     * @param count How many tasks were affected.
     * @param operation Human-readable label: "deleted" or "completed".
     * @param error null on success; error message string on failure.
     */
    data class BulkOperationDone(val count: Int, val operation: String, val error: String? = null) : AgendaUiEvent
}
