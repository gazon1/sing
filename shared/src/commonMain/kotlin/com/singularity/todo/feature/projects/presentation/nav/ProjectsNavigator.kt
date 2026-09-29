package com.singularity.todo.feature.projects.presentation.nav

import androidx.navigation3.runtime.NavBackStack
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.ProjectsRoute
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * Type-safe navigation API for screens inside the projects nested graph.
 *
 * @param backStack The nested [NavBackStack][NavBackStack] to operate on.
 * @param onExitGraph Called when the user should exit the nested graph.
 *                    The optional [AppDestination] argument allows the inner graph
 *                    to signal a destination to navigate to in the outer graph.
 */
open class ProjectsNavigator(
    private val backStack: NavBackStack<ProjectsRoute>,
    protected val onExitGraph: (AppDestination?) -> Unit,
) {

    /** Push a project detail onto the stack. */
    open fun openDetail(id: ProjectId) {
        backStack.add(ProjectsRoute.Detail(id))
    }

    /** Push the editor onto the stack (null = create mode). */
    open fun openEditor(id: ProjectId? = null) {
        backStack.add(ProjectsRoute.Editor(id))
    }

    /**
     * Exit the nested graph and navigate to the task list filtered by project
     * in the outer graph.
     */
    open fun openTasks(projectId: ProjectId) {
        onExitGraph(AppDestination.AgendaGraph(AgendaStartRoute.Project(projectId.value)))
    }

    /**
     * Exit the nested graph and navigate to a task detail in the outer graph.
     */
    open fun openTask(taskId: TaskId) {
        onExitGraph(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail(taskId.value)))
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
