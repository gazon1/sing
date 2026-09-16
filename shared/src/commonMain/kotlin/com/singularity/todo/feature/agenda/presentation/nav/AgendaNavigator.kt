package com.singularity.todo.feature.agenda.presentation.nav

import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * Type-safe navigation API for screens inside the Agenda nested graph.
 *
 * @param onNavigateToTask Called when the user taps a task — navigates to the
 *                        task detail in the outer TasksGraph.
 * @param onShowContextMenu Called when the user long-presses a task — shows
 *                          the task context menu.
 */
open class AgendaNavigator(
    private val onNavigateToTask: (TaskId) -> Unit,
    private val onShowContextMenu: (TaskId) -> Unit,
) {

    /** Navigate to the task detail screen in the outer tasks graph. */
    open fun openTask(taskId: TaskId) {
        onNavigateToTask(taskId)
    }

    /** Show the task context menu (bottom sheet or popup). */
    open fun showTaskContextMenu(taskId: TaskId) {
        onShowContextMenu(taskId)
    }
}
