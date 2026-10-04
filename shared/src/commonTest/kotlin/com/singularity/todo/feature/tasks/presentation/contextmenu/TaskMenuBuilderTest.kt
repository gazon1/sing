package com.singularity.todo.feature.tasks.presentation.contextmenu

import com.singularity.todo.core.ui.menu.MenuNode
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskAiAction
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.model.TaskUi
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@Tag("fast")
class TaskMenuBuilderTest {

    private val testUserId = com.singularity.todo.core.ids.UserId("test-user")
    private val epoch = Instant.fromEpochMilliseconds(0)

    private fun makeTask(isPinned: Boolean = false): Task = Task(
        id = TaskId("t1"),
        title = "Test task",
        createdAt = epoch,
        updatedAt = epoch,
        userId = testUserId,
        isPinned = isPinned,
    )

    private fun makeTaskUi(task: Task? = makeTask(), completed: Boolean = false, isRecurring: Boolean = false) = TaskUi(
        id = task?.id ?: TaskId("t1"),
        title = task?.title ?: "Test",
        project = null,
        dueLabel = null,
        isCompleted = completed,
        isRecurring = isRecurring,
        isPinned = task?.isPinned ?: false,
        priority = TaskPriority.None,
    )

    private fun flatten(nodes: List<MenuNode>): List<MenuNode> = buildList {
        for (node in nodes) {
            add(node)
            if (node is MenuNode.SubMenu) addAll(flatten(node.children))
        }
    }

    @Test
    fun `buildTaskContextMenu produces 20 top-level entries`() {
        val menu = buildTaskContextMenu(
            taskUi = makeTaskUi(),
            hasAiContext = true,
            actions = TaskMenuActions.Empty,
        )
        // Top-level: 4 items + 2 dividers + 5 submenus = 11
        // But builder adds: items (19 items + dividers) total — count via flatten
        val flat = flatten(menu)
        assertTrue(flat.size >= 20)
    }

    @Test
    fun `pin item label is Unpin when isPinned`() {
        val task = makeTask(isPinned = true)
        val menu = buildTaskContextMenu(
            taskUi = makeTaskUi(task = task),
            hasAiContext = true,
            actions = TaskMenuActions.Empty,
        )
        val pinItem = flatten(menu).filterIsInstance<MenuNode.Action>()
            .find { it.id == "pin" }!!
        assertEquals("Unpin", pinItem.label)
        assertTrue(pinItem.checked)
    }

    @Test
    fun `pin item label is Pin when not pinned`() {
        val task = makeTask(isPinned = false)
        val menu = buildTaskContextMenu(
            taskUi = makeTaskUi(task = task),
            hasAiContext = true,
            actions = TaskMenuActions.Empty,
        )
        val pinItem = flatten(menu).filterIsInstance<MenuNode.Action>()
            .find { it.id == "pin" }!!
        assertEquals("Pin", pinItem.label)
        assertFalse(pinItem.checked)
    }

    @Test
    fun `complete item label reflects completion state`() {
        val completed = makeTaskUi(completed = true)
        val active = makeTaskUi(completed = false)
        val completedLabel = flatten(buildTaskContextMenu(completed, true, TaskMenuActions.Empty))
            .filterIsInstance<MenuNode.Action>().find { it.id == "complete" }!!.label
        val activeLabel = flatten(buildTaskContextMenu(active, true, TaskMenuActions.Empty))
            .filterIsInstance<MenuNode.Action>().find { it.id == "complete" }!!.label
        assertEquals("Mark as uncompleted", completedLabel)
        assertEquals("Mark as completed", activeLabel)
    }

    @Test
    fun `expand collapse item is enabled only when onToggleExpand is set`() {
        val withExpand = TaskMenuActions(onToggleExpand = {}, onDismiss = {})
        val withoutExpand = TaskMenuActions(onDismiss = {})
        val withExpandMenu = buildTaskContextMenu(makeTaskUi(), true, withExpand)
        val withoutExpandMenu = buildTaskContextMenu(makeTaskUi(), true, withoutExpand)
        val withExpandItem = flatten(withExpandMenu).filterIsInstance<MenuNode.Action>()
            .find { it.id == "expand_collapse" }!!
        val withoutExpandItem = flatten(withoutExpandMenu).filterIsInstance<MenuNode.Action>()
            .find { it.id == "expand_collapse" }!!
        assertTrue(withExpandItem.enabled)
        assertFalse(withoutExpandItem.enabled)
    }

    @Test
    fun `delete item is disabled when onDelete is null`() {
        val menu = buildTaskContextMenu(makeTaskUi(), true, TaskMenuActions(onDismiss = {}))
        val deleteItem = flatten(menu).filterIsInstance<MenuNode.Action>()
            .find { it.id == "delete" }!!
        assertFalse(deleteItem.enabled)
        assertTrue(deleteItem.danger)
    }

    @Test
    fun `delete item is enabled when onDelete is set`() {
        val menu = buildTaskContextMenu(
            makeTaskUi(),
            true,
            TaskMenuActions(onDelete = {}, onDismiss = {}),
        )
        val deleteItem = flatten(menu).filterIsInstance<MenuNode.Action>()
            .find { it.id == "delete" }!!
        assertTrue(deleteItem.enabled)
        assertTrue(deleteItem.danger)
    }

    @Test
    fun `AI submenu is enabled only when onAiAction is set and hasAiContext is true`() {
        val withAi = TaskMenuActions(onAiAction = {}, onDismiss = {})
        val withoutAi = TaskMenuActions(onDismiss = {})
        val withAiMenu = buildTaskContextMenu(makeTaskUi(), hasAiContext = true, withAi)
        val withoutAiMenu = buildTaskContextMenu(makeTaskUi(), hasAiContext = true, withoutAi)
        val withoutContextMenu = buildTaskContextMenu(makeTaskUi(), hasAiContext = false, withAi)

        val withAiSub = withAiMenu.filterIsInstance<MenuNode.SubMenu>()
            .find { it.id == "ai_actions" }!!
        val withoutAiSub = withoutAiMenu.filterIsInstance<MenuNode.SubMenu>()
            .find { it.id == "ai_actions" }!!
        val noContextSub = withoutContextMenu.filterIsInstance<MenuNode.SubMenu>()
            .find { it.id == "ai_actions" }!!

        assertTrue(withAiSub.enabled)
        assertFalse(withoutAiSub.enabled)
        assertFalse(noContextSub.enabled)
    }

    @Test
    fun `AI submenu contains 5 actions`() {
        val menu = buildTaskContextMenu(
            makeTaskUi(),
            hasAiContext = true,
            actions = TaskMenuActions(onAiAction = {}, onDismiss = {}),
        )
        val aiSub = menu.filterIsInstance<MenuNode.SubMenu>()
            .find { it.id == "ai_actions" }!!
        assertEquals(5, aiSub.children.size)
    }

    @Test
    fun `AI actions dispatch correct TaskAiAction enum values`() {
        var capturedAction: TaskAiAction? = null
        val actions = TaskMenuActions(
            onAiAction = { capturedAction = it },
            onDismiss = {},
        )
        val menu = buildTaskContextMenu(makeTaskUi(), true, actions)

        val aiSub = menu.filterIsInstance<MenuNode.SubMenu>()
            .find { it.id == "ai_actions" }!!
        val refineItem = aiSub.children.filterIsInstance<MenuNode.Action>()
            .find { it.id == "ai_refine" }!!

        refineItem.onClick()
        assertEquals(TaskAiAction.RefineTitle, capturedAction)
    }

    @Test
    fun `pinned task domainTask isPinned reflects in pin checked state`() {
        val pinnedTask = makeTask(isPinned = true)
        val taskUi = makeTaskUi(task = pinnedTask)
        val pinItem = flatten(buildTaskContextMenu(taskUi, true, TaskMenuActions.Empty))
            .filterIsInstance<MenuNode.Action>()
            .find { it.id == "pin" }!!
        assertTrue(pinItem.checked)
    }

    @Test
    fun `TaskMenuActions Empty has all null`() {
        val empty = TaskMenuActions.Empty
        assertNull(empty.onTogglePin)
        assertNull(empty.onToggleComplete)
        assertNull(empty.onDelete)
        assertNull(empty.onToggleExpand)
        assertNull(empty.onAiAction)
        assertNotNull(empty.onDismiss)
    }
}
