package com.singularity.todo.feature.tasks.presentation.nav

import androidx.navigation3.runtime.NavBackStack
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate

/**
 * Type-safe navigation API for screens inside the tasks nested graph.
 *
 * @param backStack The nested [NavBackStack][NavBackStack] to operate on.
 * @param onExitGraph Called when the user should exit the nested graph.
 *                    The optional [AppDestination] argument allows the inner graph
 *                    to signal a destination to navigate to in the outer graph.
 */
open class TasksNavigator(
    private val backStack: NavBackStack<TasksRoute>,
    private val onExitGraph: (AppDestination?) -> Unit,
) {

    /** Push a task detail onto the stack. */
    open fun openDetail(id: TaskId) {
        backStack.add(TasksRoute.Detail(id))
    }

    /** Push the create screen onto the stack. */
    open fun openCreate(initialDueDate: LocalDate? = null) {
        backStack.add(TasksRoute.Create(initialDueDate))
    }

    /**
     * Exit the nested graph and navigate to a project in the outer graph.
     */
    open fun openProject(projectId: ProjectId) {
        onExitGraph(AppDestination.ProjectDetail(projectId.value))
    }

    /**
     * Go back one entry.
     * - If stack size > 1: pop last entry.
     * - If stack size == 1 (at start route): exit the nested graph with no destination.
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
