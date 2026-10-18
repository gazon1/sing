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
    data class Project(val projectId: ProjectId) : AgendaStartRoute

    /** All tasks with a specific tag. */
    @Serializable
    data class Tag(val tagId: TagId) : AgendaStartRoute

    /** All tasks matching all of the given tags (matchAll). */
    @Serializable
    data class Tags(val tagIds: Set<TagId>) : AgendaStartRoute

    /** Saved views list. */
    @Serializable
    data object SavedAgendaList : AgendaStartRoute

    /** Display the tasks matching a saved view's definition. */
    @Serializable
    data class SavedAgendaResults(val viewId: SavedAgendaViewId) : AgendaStartRoute

    /** Edit a specific saved view. */
    @Serializable
    data class SavedAgendaEdit(val viewId: SavedAgendaViewId) : AgendaStartRoute

    /** Create a new saved view, optionally seeded from an existing [AgendaDefinition]. */
    @Serializable
    data object SavedAgendaCreate : AgendaStartRoute

    /** All tasks blocked by an unfinished dependency. */
    @Serializable
    data object Blocked : AgendaStartRoute

    /** Stagnant tasks — undated, incomplete, and untouched for at least 21 days. */
    @Serializable
    data object Stagnant : AgendaStartRoute

    /**
     * Overdue tasks only — past their due date and still open.
     *
     * Used by the Statistics health drill-down to navigate directly to overdue tasks
     * rather than to [Today], which shows all today's tasks with overdue at the top.
     * The distinction matters because the Today tab's title and prefill context differ.
     */
    @Serializable
    data object Overdue : AgendaStartRoute
}
