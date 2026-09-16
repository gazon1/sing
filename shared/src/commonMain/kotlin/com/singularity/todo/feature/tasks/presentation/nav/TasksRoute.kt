package com.singularity.todo.feature.tasks.presentation.nav

import androidx.navigation3.runtime.NavKey
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * Navigation routes for the tasks nested graph.
 *
 * Lives inside [TasksNavGraph] which provides its own NavBackStack.
 *
 * @Serializable because [TasksNavGraph] uses a [SavedStateConfiguration]-backed
 * [androidx.navigation3.runtime.rememberNavBackStack], which serializes the stack via
 * [androidx.navigation3.runtime.serialization.NavBackStackSerializer]. Even on desktop
 * (where process-death is irrelevant), the [androidx.compose.runtime.saveable.SaveableStateHolder]
 * still encodes the stack on each remember — requiring all [NavKey] types to be serializable.
 */
@Serializable
sealed interface TasksRoute : NavKey {

    /**
     * List variant — the start of each branch of the tasks graph.
     * Carries the filter type as a derived property (not stored in route args).
     */
    sealed interface List : TasksRoute {
        val projectId: ProjectId?
    }

    @Serializable
    data class Inbox(override val projectId: ProjectId? = null) : List {
        val filter: ListFilter get() = ListFilter.Inbox
    }

    @Serializable
    data class Today(override val projectId: ProjectId? = null) : List {
        val filter: ListFilter get() = ListFilter.Today
    }

    @Serializable
    data class ByProject(override val projectId: ProjectId) : List {
        val filter: ListFilter get() = ListFilter.ByProject
    }

    @Serializable
    data class Detail(val taskId: TaskId) : TasksRoute

    @Serializable
    data class Create(val initialDueDate: LocalDate? = null) : TasksRoute
}

enum class ListFilter { Inbox, Today, ByProject }

/**
 * Converts a [ListFilter] to the domain [TaskFilter].
 * Used when entering the nested graph to apply the correct filter to [TasksViewModel].
 */
fun ListFilter.toDomainFilter(projectId: ProjectId?): TaskFilter = when (this) {
    ListFilter.Inbox -> TaskFilter.Inbox
    ListFilter.Today -> TaskFilter.Today
    ListFilter.ByProject -> TaskFilter.ByProject(projectId!!)
}
