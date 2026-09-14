package com.singularity.todo.feature.tasks.presentation.nav

import androidx.navigation3.runtime.NavKey
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate

/**
 * Navigation routes for the tasks nested graph.
 *
 * Lives inside [TasksNavGraph] which provides its own NavBackStack.
 * NOT @Serializable — the nested graph uses an empty SavedStateConfiguration
 * so routes are kept in memory only. If SavedState is needed, add
 * @Serializable and register polymorphic serializers in SavedStateConfiguration.
 */
sealed interface TasksRoute : NavKey {

    /**
     * List variant — the start of each branch of the tasks graph.
     * Carries the filter type as a derived property (not stored in route args).
     */
    sealed interface List : TasksRoute {
        val projectId: ProjectId?
    }

    data class Inbox(override val projectId: ProjectId? = null) : List {
        val filter: ListFilter get() = ListFilter.Inbox
    }

    data class Today(override val projectId: ProjectId? = null) : List {
        val filter: ListFilter get() = ListFilter.Today
    }

    data class ByProject(override val projectId: ProjectId) : List {
        val filter: ListFilter get() = ListFilter.ByProject
    }

    data class Detail(val taskId: TaskId) : TasksRoute

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
