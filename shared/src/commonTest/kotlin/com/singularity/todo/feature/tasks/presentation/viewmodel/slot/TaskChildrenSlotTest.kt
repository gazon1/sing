@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.feature.tasks.domain.model.BlockedCloseRequest
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers the child collections: checklist, subtasks, and attachments. */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class TaskChildrenSlotTest {

    private fun slot(
        fakes: SlotFakes,
        source: TaskSource,
        scope: kotlinx.coroutines.CoroutineScope,
        allTasks: List<Task> = emptyList(),
        onBlockedClose: (BlockedCloseRequest) -> Unit = {},
        onSaved: (String) -> Unit = {},
    ) = TaskChildrenSlot(
        taskId = TaskId("t1"),
        core = fakes.core(),
        children = fakes.children(),
        context = fakes.context(),
        scope = testSlotScope(scope),
        taskFlow = source.state,
        allTasks = MutableStateFlow(allTasks),
        onError = {},
        onSaved = onSaved,
        onBlockedClose = onBlockedClose,
    )

    @Test
    fun `state is empty before anything is added`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(task("t1"))
        val slot = slot(fakes, source, backgroundScope)
        runCurrent()

        assertEquals(TaskChildrenState(), slot.state.value)
    }

    @Test
    fun `add checklist item creates it in the repository`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(task("t1"))
        val slot = slot(fakes, source, backgroundScope)
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.AddChecklistItem("New item"))
        runCurrent()

        val items = fakes.checklistRepo.items.value.values.toList()
        assertEquals(1, items.size)
        assertEquals("New item", items[0].title)
        assertFalse(items[0].isCompleted)
    }

    @Test
    fun `blank checklist title is ignored`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(task("t1"))
        val slot = slot(fakes, source, backgroundScope)
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.AddChecklistItem("   "))
        runCurrent()

        assertTrue(fakes.checklistRepo.items.value.isEmpty())
    }

    @Test
    fun `toggle checklist item flips completion`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(task("t1"))
        val slot = slot(fakes, source, backgroundScope)
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.AddChecklistItem("Toggle me"))
        runCurrent()
        val item = fakes.checklistRepo.items.value.values.first()
        assertFalse(item.isCompleted)

        slot.onIntent(TaskDetailIntent.Domain.ToggleChecklistItem(item))
        runCurrent()

        assertTrue(fakes.checklistRepo.items.value[item.id.value]?.isCompleted == true)
    }

    @Test
    fun `add subtask parents it to the current task`() = runTest {
        val fakes = SlotFakes()
        val errors = mutableListOf<String>()
        val saved = mutableListOf<String>()
        val source = TaskSource(task("t1"))
        val slot = TaskChildrenSlot(
            taskId = TaskId("t1"),
            core = fakes.core(),
            children = fakes.children(),
            context = fakes.context(),
            scope = testSlotScope(backgroundScope),
            taskFlow = source.state,
            allTasks = MutableStateFlow(emptyList()),
            onError = { errors += it },
            onSaved = { saved += it },
            onBlockedClose = {},
        )
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.AddSubtask("Child"))
        runCurrent()

        val subtasks = fakes.taskRepo.tasks.value.values.filter { it.parentTaskId == TaskId("t1") }
        assertEquals(
            1,
            subtasks.size,
            "keys=${fakes.taskRepo.tasks.value.keys} errors=$errors saved=$saved",
        )
        assertEquals("Child", subtasks.first().title)
    }

    @Test
    fun `delete subtask soft-deletes it`() = runTest {
        val fakes = SlotFakes()
        val child = task("c1", title = "Child")
        fakes.taskRepo.seed(task("t1"), child)
        val source = TaskSource(task("t1"))
        val slot = slot(fakes, source, backgroundScope)
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.DeleteSubtask(child))
        runCurrent()

        assertNotNull(fakes.taskRepo.tasks.value["c1"]?.archivedAt)
    }

    @Test
    fun `add url attachment is reported as saved`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(task("t1"))
        val saved = mutableListOf<String>()
        val slot = slot(fakes, source, backgroundScope) { saved += it }
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.AddUrlAttachment("https://example.com", "Example"))
        runCurrent()

        assertEquals(listOf("Attachment added"), saved)
    }

    // ─── The blocked-completion guard ─────────────────────────────────────────

    @Test
    fun `a subtask with an unfinished prerequisite is not closed and asks instead`() = runTest {
        val fakes = SlotFakes()
        val dep = task("dep", "Dependency")
        val child = task("c1", "Child").copy(parentTaskId = TaskId("t1"), dependsOn = setOf(TaskId("dep")))
        fakes.taskRepo.seed(task("t1"), dep, child)
        val source = TaskSource(task("t1"))
        val blocked = mutableListOf<BlockedCloseRequest>()
        val slot = slot(
            fakes,
            source,
            backgroundScope,
            allTasks = listOf(dep, child),
            onBlockedClose = { blocked += it },
        )
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.ToggleSubtask(child))
        runCurrent()

        assertNull(
            fakes.taskRepo.tasks.value["c1"]?.completedAt,
            "a blocked subtask must not be closed on the first request",
        )
        assertEquals(1, blocked.size, "the user must be asked")
        assertEquals(listOf("Dependency"), blocked.single().blockerTitles)
    }

    @Test
    fun `confirming the request closes the subtask`() = runTest {
        val fakes = SlotFakes()
        val dep = task("dep", "Dependency")
        val child = task("c1", "Child").copy(parentTaskId = TaskId("t1"), dependsOn = setOf(TaskId("dep")))
        fakes.taskRepo.seed(task("t1"), dep, child)
        val source = TaskSource(task("t1"))
        val blocked = mutableListOf<BlockedCloseRequest>()
        val slot = slot(
            fakes,
            source,
            backgroundScope,
            allTasks = listOf(dep, child),
            onBlockedClose = { blocked += it },
        )
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.ToggleSubtask(child))
        runCurrent()
        slot.onIntent(TaskDetailIntent.Domain.ConfirmBlockedClose(TaskId("c1")))
        runCurrent()

        assertNotNull(fakes.taskRepo.tasks.value["c1"]?.completedAt)
    }

    @Test
    fun `an unblocked subtask closes without a question`() = runTest {
        val fakes = SlotFakes()
        val child = task("c1", "Child").copy(parentTaskId = TaskId("t1"))
        fakes.taskRepo.seed(task("t1"), child)
        val source = TaskSource(task("t1"))
        val blocked = mutableListOf<BlockedCloseRequest>()
        val slot = slot(
            fakes,
            source,
            backgroundScope,
            allTasks = listOf(child),
            onBlockedClose = { blocked += it },
        )
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.ToggleSubtask(child))
        runCurrent()

        assertNotNull(fakes.taskRepo.tasks.value["c1"]?.completedAt)
        assertTrue(blocked.isEmpty(), "an unblocked subtask must not raise a confirmation")
    }
}
