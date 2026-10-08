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

    /** User tapped '+' in a section header to create a task pre-filled from that section. */
    data class CreateInSection(val sectionId: String) : AgendaIntent

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
