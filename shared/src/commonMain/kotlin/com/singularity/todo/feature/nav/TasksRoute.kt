package com.singularity.todo.feature.nav

import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * Navigation routes for the tasks nested graph.
 *
 * Lives inside [TasksNavGraph] which provides its own NavBackStack.
 *
 * ## Active routes (MR1+)
 * - [Detail] — task detail screen
 * - [Create] — new task screen
 *
 * ## Deprecated routes (AgendaEngine MR1)
 * Inbox/Today/Upcoming/ByProject tabs were replaced by AgendaEngine.
 * See [com.singularity.todo.feature.nav.AgendaStartRoute] and [com.singularity.todo.feature.nav.AppDestination.AgendaGraph].
 *
 * @Serializable because [TasksNavGraph] uses a [SavedStateConfiguration]-backed
 * [androidx.navigation3.runtime.rememberNavBackStack], which serializes the stack via
 * [androidx.navigation3.runtime.serialization.NavBackStackSerializer].
 */
@Serializable
sealed interface TasksRoute : AppNavKey {

    /**
     * A task's detail screen.
     *
     * [sourceViewId] is the saved agenda view the task was opened from, when there was
     * one. The task's detail screen is the only place that can set a reminder about the
     * task, and the reminder is what points back at the list the person was looking at.
     */
    @Serializable
    data class Detail(val taskId: TaskId, val sourceViewId: SavedAgendaViewId? = null) : TasksRoute

    @Serializable
    data class Create(val initialDueDate: LocalDate? = null, val sectionPrefillKey: String? = null) : TasksRoute
}
