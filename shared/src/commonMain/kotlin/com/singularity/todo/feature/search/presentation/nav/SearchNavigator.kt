package com.singularity.todo.feature.search.presentation.nav

import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * Type-safe navigation API for screens inside the search nested graph.
 *
 * @param onExitGraph Called when the user should exit the nested graph.
 *                    The optional [AppDestination] argument allows the inner graph
 *                    to signal a destination to navigate to in the outer graph.
 */
open class SearchNavigator(protected val onExitGraph: (AppDestination?) -> Unit) {

    /**
     * Open a task detail in the tasks graph.
     */
    open fun openTask(taskId: TaskId) {
        onExitGraph(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail(taskId.value)))
    }

    /**
     * Open a note preview in the notes graph.
     */
    open fun openNote(noteId: NoteId) {
        onExitGraph(AppDestination.NotesGraph(AppDestination.NotesStartRoute.Preview(noteId.value)))
    }

    /**
     * Open a project detail in the outer graph.
     */
    open fun openProject(projectId: ProjectId) {
        onExitGraph(AppDestination.ProjectDetail(projectId.value))
    }

    /**
     * Open the agenda filtered to a tag.
     *
     * A tag has no detail screen of its own — a tag *is* a filter over tasks — so this
     * routes into the agenda graph at [AgendaStartRoute.Tag] rather than inventing a
     * destination that would have nothing to render.
     */
    open fun openTag(tagId: TagId) {
        onExitGraph(AppDestination.AgendaGraph(AgendaStartRoute.Tag(tagId.value)))
    }

    /**
     * Go back — exits the search graph back to the outer app back stack.
     */
    open fun back() {
        onExitGraph(null)
    }
}
