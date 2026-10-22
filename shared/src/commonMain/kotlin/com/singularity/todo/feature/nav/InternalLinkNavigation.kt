package com.singularity.todo.feature.nav

import com.singularity.todo.core.links.InternalLinkSchemes
import com.singularity.todo.core.links.InternalLinkTarget
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * The screen an internal link points at.
 *
 * ## Why this is here and not in `core`
 *
 * A link says *what* to open; it does not say *how* to open it. The vocabulary of
 * schemes belongs to `core`; the mapping from a scheme to a screen belongs to the
 * navigation layer, because that is where the screens are. Keeping the two apart is
 * what lets a link be read without pulling the navigation graph into every feature
 * that stores one.
 *
 * ## What happens to an unknown scheme
 *
 * Nothing. A link the app does not recognise is not an error the user caused, and it
 * must not become a crash or a blank screen.
 *
 * @param target the link that was tapped.
 * @return the destination to open, or null when the link names nothing the app
 *   knows how to show.
 */
fun internalLinkDestination(target: InternalLinkTarget): AppDestination? = when (target.scheme) {
    InternalLinkSchemes.NOTE -> AppDestination.NotesGraph(
        AppDestination.NotesStartRoute.Preview(NoteId.fromString(target.id)),
    )

    InternalLinkSchemes.TASK -> AppDestination.TasksGraph(
        AppDestination.TasksStartRoute.Detail(TaskId.fromString(target.id)),
    )

    else -> null
}
