package com.singularity.todo.feature.calendar.presentation.nav

import androidx.navigation3.runtime.NavBackStack
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate

/**
 * Type-safe navigation API for screens inside the Calendar nested graph.
 *
 * @param backStack The nested [NavBackStack][NavBackStack] to operate on.
 * @param onExitGraph Called when the user should exit the nested graph.
 *                    The optional [AppDestination] argument allows the inner graph
 *                    to signal a destination to navigate to in the outer graph
 *                    (e.g. [AppDestination.TasksGraph] to open a task).
 */
open class CalendarNavigator(
    private val backStack: NavBackStack<CalendarRoute>,
    private val onExitGraph: (AppDestination?) -> Unit,
) {

    /** Navigate to a specific day, pushing a [CalendarRoute.Day] entry. */
    open fun openDay(date: LocalDate) {
        backStack.add(CalendarRoute.Day(date.toString()))
    }

    /**
     * Navigate to the task detail screen in the outer tasks graph.
     * This exits the Calendar nested graph and opens the task in TasksGraph.
     */
    open fun openTask(taskId: TaskId) {
        onExitGraph(
            AppDestination.TasksGraph(
                start = AppDestination.TasksStartRoute.Detail(taskId.value),
            ),
        )
    }

    /**
     * Go back one entry in the Calendar graph.
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

    /** Force-close the entire nested graph with no outer-nav result. */
    open fun closeGraph() {
        onExitGraph(null)
    }
}
