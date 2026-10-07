package com.singularity.todo.feature.tasks.presentation.nav

import androidx.navigation3.runtime.NavBackStack
import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.TasksRoute
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.projects.domain.model.ProjectId
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
    protected val onExitGraph: (AppDestination?) -> Unit,
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
     * Exit the nested graph and open a note preview in the notes graph.
     *
     * Used by [com.singularity.todo.feature.tasks.presentation.components.detail.LinkedBacklinksCard]:
     * a `task://` backlink is the only route from a task to the note that links to it,
     * and the notes graph is not on this screen's back stack.
     */
    open fun openNote(noteId: NoteId) {
        onExitGraph(AppDestination.NotesGraph(AppDestination.NotesStartRoute.Preview(noteId)))
    }

    /**
     * Exit the nested graph and open the note editor pre-attached to [taskId].
     * The created note will be linked to the task via [com.singularity.todo.feature.notes.NotesRepository.createForTask].
     *
     * Used by [com.singularity.todo.feature.tasks.presentation.components.detail.LogbookSection].
     */
    open fun openCreateNote(taskId: TaskId) {
        onExitGraph(AppDestination.NotesGraph(AppDestination.NotesStartRoute.EditorForTask(taskId)))
    }

    /**
     * Exit the nested graph and open an attachment in the viewer.
     *
     * The viewer lives on the app-level stack, not this graph's, so it is reached the
     * same way a project or a note is: by naming an outer destination. What the viewer
     * shows is decided from the attachment's own type once it is there — see
     * [com.singularity.todo.core.attachments.AttachmentViewerRoute].
     */
    open fun openAttachment(attachmentId: AttachmentId) {
        onExitGraph(AppDestination.AttachmentViewer(attachmentId))
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
