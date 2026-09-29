package com.singularity.todo.feature.nav

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

    @Serializable
    data class Detail(val taskId: TaskId) : TasksRoute

    @Serializable
    data class Create(val initialDueDate: LocalDate? = null) : TasksRoute
}
