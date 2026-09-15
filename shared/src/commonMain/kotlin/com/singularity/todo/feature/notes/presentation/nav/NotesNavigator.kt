package com.singularity.todo.feature.notes.presentation.nav

import androidx.navigation3.runtime.NavBackStack
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * Type-safe navigation API for screens inside the notes nested graph.
 *
 * @param backStack The nested [NavBackStack][NavBackStack] to operate on.
 * @param onExitGraph Called when the user should exit the nested graph.
 *                    The optional [AppDestination] argument allows the inner graph
 *                    to signal a destination to navigate to in the outer graph.
 */
open class NotesNavigator(
    private val backStack: NavBackStack<NotesRoute>,
    private val onExitGraph: (AppDestination?) -> Unit,
) {

    /** Push a note preview onto the stack. */
    open fun openPreview(noteId: NoteId) {
        backStack.add(NotesRoute.Preview(noteId))
    }

    /** Push the editor onto the stack. Pass null noteId to create a new note. */
    open fun openEditor(noteId: NoteId? = null) {
        backStack.add(NotesRoute.Editor(noteId))
    }

    /**
     * Exit the notes nested graph and navigate to the tasks tab.
     * Used when the user taps a [[task]] wikilink inside a note.
     */
    open fun openTask(taskId: TaskId) {
        onExitGraph(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Inbox))
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
