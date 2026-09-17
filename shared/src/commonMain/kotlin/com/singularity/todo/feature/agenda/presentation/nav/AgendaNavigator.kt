package com.singularity.todo.feature.agenda.presentation.nav

import androidx.navigation3.runtime.NavBackStack
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * Type-safe navigation API for screens inside the Agenda nested graph.
 *
 * @param backStack The nested [NavBackStack][NavBackStack] to operate on.
 * @param onExitGraph Called when the user should exit the nested graph, optionally
 *                    passing a destination to navigate to in the outer graph.
 */
open class AgendaNavigator(
    private val backStack: NavBackStack<AgendaStartRoute>,
    protected val onExitGraph: (AppDestination?) -> Unit,
) {

    /** Navigate to the task detail screen in the outer tasks graph. */
    open fun openTask(taskId: TaskId) {
        onExitGraph(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail(taskId.value)))
    }

    /** Show the task context menu (bottom sheet or popup). */
    open fun showTaskContextMenu(taskId: TaskId) {
        // Context menu is handled via routing state in AgendaContent — no nav needed
    }

    /** Push the saved agenda views list onto the stack. */
    open fun openSavedAgendaList() {
        backStack.add(AgendaStartRoute.SavedAgendaList)
    }

    /** Push the saved agenda edit screen onto the stack. */
    open fun openSavedAgendaEdit(viewId: SavedAgendaViewId) {
        backStack.add(AgendaStartRoute.SavedAgendaEdit(viewId.raw))
    }

    /**
     * Go back one entry.
     * - If stack size > 1: pop last entry.
     * - If stack size == 1 (at start route): exit the nested graph.
     */
    open fun back() {
        if (backStack.size <= 1) {
            onExitGraph(null)
        } else {
            backStack.removeLastOrNull()
        }
    }
}
