package com.singularity.todo.feature.nav

import com.singularity.todo.core.links.InternalLinkSchemes
import com.singularity.todo.core.links.InternalLinkTarget
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Where an internal link points.
 *
 * A link names a thing; the navigation layer decides the screen that thing is
 * shown on. These cases pin that mapping, including the case where the link names
 * something the app does not know how to show.
 */
@Tag("fast")
class InternalLinkNavigationTest {

    @Test
    fun `a note link opens the note`() {
        val destination = internalLinkDestination(InternalLinkTarget(InternalLinkSchemes.NOTE, "n1"))
        assertEquals(
            AppDestination.NotesGraph(AppDestination.NotesStartRoute.Preview(NoteId.fromString("n1"))),
            destination,
        )
    }

    @Test
    fun `a task link opens the task`() {
        val destination = internalLinkDestination(InternalLinkTarget(InternalLinkSchemes.TASK, "t1"))
        assertEquals(
            AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail(TaskId.fromString("t1"))),
            destination,
        )
    }

    @Test
    fun `a link whose scheme is unknown opens nothing`() {
        assertNull(internalLinkDestination(InternalLinkTarget("https", "example.com")))
        assertNull(internalLinkDestination(InternalLinkTarget("", "n1")))
    }
}
