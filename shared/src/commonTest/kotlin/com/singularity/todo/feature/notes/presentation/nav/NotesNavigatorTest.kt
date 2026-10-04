package com.singularity.todo.feature.notes.presentation.nav

import androidx.navigation3.runtime.NavBackStack
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.NotesRoute
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [NotesNavigator] exit-graph contract — most importantly the B1 companion fix:
 * a task *id* must open the task **detail**, not the create screen.
 *
 * Fake stack, no mocks: [NavBackStack] is a plain list-backed structure and the
 * `onExitGraph` callback is captured directly (project test convention: fakes).
 */
@Tag("fast")
class NotesNavigatorTest {

    private val stack: NavBackStack<NotesRoute> = NavBackStack(NotesRoute.List)

    private var exitedWith: AppDestination? = null

    private val navigator = NotesNavigator(stack) { exitedWith = it }

    @Test
    fun `openTask exits to the task detail route carrying the id`() {
        navigator.openTask(TaskId("t1"))

        assertEquals(
            AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail(TaskId("t1"))),
            exitedWith,
            "a task id must open TasksGraph(Detail(id)) — not the create screen",
        )
    }

    @Test
    fun `back at the graph root exits with no destination`() {
        navigator.back()

        assertNull(exitedWith)
        assertEquals(1, stack.size, "back at the root must not pop below the start route")
    }

    @Test
    fun `back with a pushed entry pops it instead of exiting`() {
        navigator.openPreview(NoteId("n1"))

        navigator.back()

        assertNull(exitedWith, "popping an inner entry is not a graph exit")
        assertEquals(listOf(NotesRoute.List), stack.toList())
    }

    @Test
    fun `closeGraph always exits with no destination`() {
        navigator.openEditor(NoteId("n1"))

        navigator.closeGraph()

        assertEquals<AppDestination?>(null, exitedWith)
    }
}
