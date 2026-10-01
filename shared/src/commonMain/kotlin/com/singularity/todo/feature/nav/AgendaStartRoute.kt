package com.singularity.todo.feature.nav

import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import kotlinx.serialization.Serializable

/**
 * Start routes for the Agenda nested graph.
 * Used by [AppDestination.AgendaGraph] to parameterise the start of the agenda.
 */
@Serializable
sealed interface AgendaStartRoute : AppNavKey {

    /** Inbox — all active tasks grouped by relative date bucket. */
    @Serializable
    data object Inbox : AgendaStartRoute

    /** Today — only today's tasks with overdue at top. */
    @Serializable
    data object Today : AgendaStartRoute

    /** Upcoming — tasks for the next 2 weeks grouped by week. */
    @Serializable
    data object Upcoming : AgendaStartRoute

    /** All tasks for a specific project. */
    @Serializable
    data class Project(val projectId: String) : AgendaStartRoute {
        val id: ProjectId get() = ProjectId.fromString(projectId)
    }

    /** All tasks with a specific tag. */
    @Serializable
    data class Tag(val tagId: String) : AgendaStartRoute {
        val id: TagId get() = TagId.fromString(tagId)
    }

    /** Saved views list. */
    @Serializable
    data object SavedAgendaList : AgendaStartRoute

    /** Display the tasks matching a saved view's definition. */
    @Serializable
    data class SavedAgendaResults(val viewId: String) : AgendaStartRoute {
        val id: SavedAgendaViewId get() = SavedAgendaViewId.fromString(viewId)
    }

    /** Edit a specific saved view. */
    @Serializable
    data class SavedAgendaEdit(val viewId: String) : AgendaStartRoute {
        val id: SavedAgendaViewId get() = SavedAgendaViewId.fromString(viewId)
    }

    /** Create a new saved view, optionally seeded from an existing [AgendaDefinition]. */
    @Serializable
    data object SavedAgendaCreate : AgendaStartRoute
}
