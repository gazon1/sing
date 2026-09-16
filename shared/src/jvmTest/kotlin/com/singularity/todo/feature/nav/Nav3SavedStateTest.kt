package com.singularity.todo.feature.nav

import androidx.navigation3.runtime.NavBackStack
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.nav.TasksRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Tests for [NavBackStack] navigation behaviour on JVM.
 *
 * These are pure JVM unit tests — no Compose runtime, no UI.
 * [NavBackStack] is a [SnapshotStateList] internally; we test it as a list.
 *
 * We call the [NavBackStack] constructor directly rather than
 * [rememberInMemoryNavBackStack] because the latter is @Composable
 * and cannot be used in a plain unit test context.
 */
class Nav3SavedStateTest {

    /** Creates a NavBackStack typed at the sealed interface level so any TasksRoute subtype can be added. */
    private fun makeStack(start: TasksRoute): NavBackStack<TasksRoute> =
        NavBackStack(start)

    @Test
    fun `NavBackStack starts with single element`() {
        val stack = makeStack(TasksRoute.Inbox())
        assertEquals(1, stack.size)
        assertEquals(TasksRoute.Inbox(), stack.last())
    }

    @Test
    fun `add appends element to the back stack`() {
        val stack = makeStack(TasksRoute.Inbox())
        stack.add(TasksRoute.Create(null))

        assertEquals(2, stack.size)
        assertEquals(TasksRoute.Inbox(), stack[0])
        assertEquals(TasksRoute.Create(null), stack[1])
        assertEquals(TasksRoute.Create(null), stack.last())
    }

    @Test
    fun `removeLastOrNull pops the top element`() {
        val stack = makeStack(TasksRoute.Inbox())
        stack.add(TasksRoute.Create(null))

        val popped = stack.removeLastOrNull()

        assertEquals(TasksRoute.Create(null), popped)
        assertEquals(1, stack.size)
        assertEquals(TasksRoute.Inbox(), stack.last())
    }

    @Test
    fun `removeLastOrNull on single element removes it and returns it`() {
        // NavBackStack.removeLastOrNull() delegates to SnapshotStateList.removeAt(lastIndex).
        // Unlike MutableList.removeLastOrNull() it does NOT guard against single-element stacks.
        // The navigator (Local*Navigator.back()) guards this at the application layer.
        val stack = makeStack(TasksRoute.Inbox())
        val popped = stack.removeLastOrNull()

        // Single-element removal: the element IS returned (SnapshotStateList removes it).
        // The navigation layer prevents this via `if (backStack.size > 1) removeLastOrNull() else onExitGraph(null)`.
        assertEquals(TasksRoute.Inbox(), popped)
        assertEquals(0, stack.size)
    }

    @Test
    fun `add with Detail payload stores the payload`() {
        val taskId = TaskId("task-42")
        val stack = makeStack(TasksRoute.Inbox())
        stack.add(TasksRoute.Detail(taskId))

        val top = stack.last()
        assertIs<TasksRoute.Detail>(top)
        assertEquals(taskId, top.taskId)
    }

    @Test
    fun `multiple pushes and pops maintain correct order`() {
        val stack = makeStack(TasksRoute.Inbox())
        stack.add(TasksRoute.Today())
        stack.add(TasksRoute.Create(null))
        stack.add(TasksRoute.Detail(TaskId("t1")))

        assertEquals(4, stack.size)
        assertEquals(TasksRoute.Inbox(), stack[0])
        assertEquals(TasksRoute.Today(), stack[1])
        assertEquals(TasksRoute.Create(null), stack[2])
        assertEquals(TasksRoute.Detail(TaskId("t1")), stack[3])

        stack.removeLastOrNull()
        stack.removeLastOrNull()

        assertEquals(2, stack.size)
        assertEquals(TasksRoute.Today(), stack.last())
    }

    @Test
    fun `ByProject route carries projectId`() {
        val projectId = ProjectId("proj-99")
        val stack = makeStack(TasksRoute.Inbox())
        stack.add(TasksRoute.ByProject(projectId))

        val top = stack.last()
        assertIs<TasksRoute.ByProject>(top)
        assertEquals(projectId, top.projectId)
    }
}
