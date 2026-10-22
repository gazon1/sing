package com.singularity.todo.feature.agenda.presentation.nav

import androidx.navigation3.runtime.NavBackStack
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.tasks.domain.model.TaskDraftKeys
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

    /**
     * Navigate to the task detail screen in the outer tasks graph.
     *
     * The saved view the person was in travels with the route: the agenda is the only
     * place that knows it, and a reminder set on the task points back at that view.
     * From a tab that is not a saved view there is nothing to point at, and the
     * reminder carries no view.
     */
    open fun openTask(taskId: TaskId) {
        onExitGraph(
            AppDestination.TasksGraph(
                AppDestination.TasksStartRoute.Detail(taskId, currentSavedViewId()),
            ),
        )
    }

    /**
     * The saved view the agenda is showing, if it is showing one.
     *
     * Both screens that list a saved view's tasks count: the results screen is where a
     * person reads the list, and the editor is where they change it. A task opened
     * from either was opened from that view.
     */
    private fun currentSavedViewId(): SavedAgendaViewId? = when (val top = backStack.lastOrNull()) {
        is AgendaStartRoute.SavedAgendaResults -> top.viewId
        is AgendaStartRoute.SavedAgendaEdit -> top.viewId
        else -> null
    }

    /** Show the task context menu (bottom sheet or popup). */
    open fun showTaskContextMenu(taskId: TaskId) {
        // Context menu is handled via routing state in AgendaContent — no nav needed
    }

    /**
     * Open the task create screen with pre-fill values from an agenda section.
     *
     * The draft the agenda wrote lives under the section's key, and the editor
     * is pointed at that key by name — the full key, built by
     * [TaskDraftKeys], so the two sides cannot build it differently.
     */
    open fun openCreateInSection(sectionId: String) {
        onExitGraph(
            AppDestination.TasksGraph(
                start = AppDestination.TasksStartRoute.Create(draftKey = TaskDraftKeys.agendaSection(sectionId)),
            ),
        )
    }

    /**
     * Open the task create screen on the draft that holds the picked days.
     *
     * The days travel in the draft, not in the route: a list of dates does not
     * belong in a back stack, and the draft survives the death of the process.
     */
    open fun openCreateWithDates(draftKey: String) {
        onExitGraph(
            AppDestination.TasksGraph(
                start = AppDestination.TasksStartRoute.Create(draftKey = draftKey),
            ),
        )
    }

    /** Push the saved agenda views list onto the stack. */
    open fun openSavedAgendaList() {
        backStack.add(AgendaStartRoute.SavedAgendaList)
    }

    /** Push the saved agenda results screen (task list for a saved view) onto the stack. */
    open fun openSavedAgendaResults(viewId: SavedAgendaViewId) {
        backStack.add(AgendaStartRoute.SavedAgendaResults(viewId))
    }

    /** Push the saved agenda edit screen onto the stack. */
    open fun openSavedAgendaEdit(viewId: SavedAgendaViewId) {
        backStack.add(AgendaStartRoute.SavedAgendaEdit(viewId))
    }

    /** Push the saved agenda create screen onto the stack. */
    open fun openSavedAgendaCreate(seed: AgendaDefinition) {
        backStack.add(AgendaStartRoute.SavedAgendaCreate)
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
