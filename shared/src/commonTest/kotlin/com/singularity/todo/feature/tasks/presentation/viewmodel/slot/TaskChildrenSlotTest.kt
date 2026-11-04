@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.tasks.domain.model.BlockedCloseRequest
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.state.TaskDetailIntent
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
        onUndoDeleteChecklistItem: (ChecklistItem) -> Unit = {},
        onUndoDeleteSubtask: (Task) -> Unit = {},
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
        onUndoDeleteChecklistItem = onUndoDeleteChecklistItem,
        onUndoDeleteSubtask = onUndoDeleteSubtask,
    )

    @Test
    fun `state is empty before anything is added`() =
        runTest {
            val fakes = SlotFakes()
            val source = TaskSource(task("t1"))
            val slot = slot(fakes, source, backgroundScope)
            runCurrent()

            assertEquals(TaskChildrenState(), slot.state.value)
        }

    @Test
    fun `add checklist item creates it in the repository`() =
        runTest {
            val fakes = SlotFakes()
            val source = TaskSource(task("t1"))
            val slot = slot(fakes, source, backgroundScope)
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.AddChecklistItem("New item"))
            runCurrent()

            val items =
                fakes.checklistRepo.items.value.values
                    .toList()
            assertEquals(1, items.size)
            assertEquals("New item", items[0].title)
            assertFalse(items[0].isCompleted)
        }

    @Test
    fun `blank checklist title is ignored`() =
        runTest {
            val fakes = SlotFakes()
            val source = TaskSource(task("t1"))
            val slot = slot(fakes, source, backgroundScope)
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.AddChecklistItem("   "))
            runCurrent()

            assertTrue(
                fakes.checklistRepo.items.value
                    .isEmpty(),
            )
        }

    @Test
    fun `toggle checklist item flips completion`() =
        runTest {
            val fakes = SlotFakes()
            val source = TaskSource(task("t1"))
            val slot = slot(fakes, source, backgroundScope)
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.AddChecklistItem("Toggle me"))
            runCurrent()
            val item =
                fakes.checklistRepo.items.value.values
                    .first()
            assertFalse(item.isCompleted)

            slot.onIntent(TaskDetailIntent.Domain.ToggleChecklistItem(item))
            runCurrent()

            assertTrue(
                fakes.checklistRepo.items.value[item.id.value]
                    ?.isCompleted == true,
            )
        }

    @Test
    fun `add subtask parents it to the current task`() =
        runTest {
            val fakes = SlotFakes()
            val errors = mutableListOf<String>()
            val saved = mutableListOf<String>()
            val source = TaskSource(task("t1"))
            val slot =
                TaskChildrenSlot(
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

            val subtasks =
                fakes.taskRepo.tasks.value.values
                    .filter { it.parentTaskId == TaskId("t1") }
            assertEquals(
                1,
                subtasks.size,
                "keys=${fakes.taskRepo.tasks.value.keys} errors=$errors saved=$saved",
            )
            assertEquals("Child", subtasks.first().title)
        }

    @Test
    fun `delete subtask soft-deletes it`() =
        runTest {
            val fakes = SlotFakes()
            val child = task("c1", title = "Child").copy(parentTaskId = TaskId("t1"))
            fakes.taskRepo.seed(task("t1"), child)
            val source = TaskSource(task("t1"))
            val slot = slot(fakes, source, backgroundScope)
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.DeleteSubtask(child))
            runCurrent()

            assertNotNull(
                fakes.taskRepo.tasks.value["c1"]
                    ?.archivedAt,
            )
        }

    // ─── The undo of a removed child ─────────────────────────────────────────

    @Test
    fun `deleting a checklist item offers an undo`() =
        runTest {
            val fakes = SlotFakes()
            val source = TaskSource(task("t1"))
            val undone = mutableListOf<ChecklistItem>()
            val slot =
                slot(
                    fakes,
                    source,
                    backgroundScope,
                    onUndoDeleteChecklistItem = { undone += it },
                )
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.AddChecklistItem("Buy milk"))
            runCurrent()
            val item =
                fakes.checklistRepo.items.value.values
                    .first()

            slot.onIntent(TaskDetailIntent.Domain.DeleteChecklistItem(item.id))
            runCurrent()

            assertEquals(1, undone.size, "the user must be offered a way back")
            assertEquals("Buy milk", undone.single().title)
        }

    @Test
    fun `the undo puts the checklist item back`() =
        runTest {
            val fakes = SlotFakes()
            val source = TaskSource(task("t1"))
            val slot = slot(fakes, source, backgroundScope)
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.AddChecklistItem("Buy milk"))
            runCurrent()
            val item =
                fakes.checklistRepo.items.value.values
                    .first()
            slot.onIntent(TaskDetailIntent.Domain.DeleteChecklistItem(item.id))
            runCurrent()
            assertNull(fakes.checklistRepo.items.value[item.id.value])

            slot.onIntent(TaskDetailIntent.Domain.RestoreChecklistItem(item))
            runCurrent()

            assertEquals(
                "Buy milk",
                fakes.checklistRepo.items.value[item.id.value]
                    ?.title,
            )
        }

    @Test
    fun `deleting a subtask offers an undo`() =
        runTest {
            val fakes = SlotFakes()
            val child = task("c1", title = "Child").copy(parentTaskId = TaskId("t1"))
            fakes.taskRepo.seed(task("t1"), child)
            val source = TaskSource(task("t1"))
            val undone = mutableListOf<Task>()
            val slot =
                slot(
                    fakes,
                    source,
                    backgroundScope,
                    onUndoDeleteSubtask = { undone += it },
                )
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.DeleteSubtask(child))
            runCurrent()

            assertEquals(1, undone.size, "the user must be offered a way back")
            assertEquals("Child", undone.single().title)
        }

    @Test
    fun `the undo puts the subtask back`() =
        runTest {
            val fakes = SlotFakes()
            val child = task("c1", title = "Child").copy(parentTaskId = TaskId("t1"))
            fakes.taskRepo.seed(task("t1"), child)
            val source = TaskSource(task("t1"))
            val slot = slot(fakes, source, backgroundScope)
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.DeleteSubtask(child))
            runCurrent()
            assertNotNull(
                fakes.taskRepo.tasks.value["c1"]
                    ?.archivedAt,
            )

            slot.onIntent(TaskDetailIntent.Domain.RestoreSubtask(TaskId("c1")))
            runCurrent()

            assertNull(
                fakes.taskRepo.tasks.value["c1"]
                    ?.archivedAt,
                "the subtask must come back from the trash",
            )
        }

    @Test
    fun `the undo refuses a task that is not a child of this task`() =
        runTest {
            val fakes = SlotFakes()
            // A subtask of another parent: this screen did not lose it, so it is
            // not this screen's to bring back.
            val other =
                task("x1", title = "Somebody else's child").copy(
                    parentTaskId = TaskId("t2"),
                    archivedAt =
                        kotlin.time.Clock.System
                            .now(),
                )
            fakes.taskRepo.seed(task("t1"), other)
            val errors = mutableListOf<String>()
            val source = TaskSource(task("t1"))
            val slot =
                TaskChildrenSlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    context = fakes.context(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                    allTasks = MutableStateFlow(emptyList()),
                    onError = { errors += it },
                    onSaved = {},
                    onBlockedClose = {},
                )
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.RestoreSubtask(TaskId("x1")))
            runCurrent()

            assertNotNull(
                fakes.taskRepo.tasks.value["x1"]
                    ?.archivedAt,
                "a task of another parent must stay in the trash",
            )
            assertEquals(listOf("Restore failed"), errors)
        }

    @Test
    fun `the undo refuses a task that is not in the trash`() =
        runTest {
            val fakes = SlotFakes()
            val child = task("c1", title = "Child").copy(parentTaskId = TaskId("t1"))
            fakes.taskRepo.seed(task("t1"), child)
            val errors = mutableListOf<String>()
            val source = TaskSource(task("t1"))
            val slot =
                TaskChildrenSlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    context = fakes.context(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                    allTasks = MutableStateFlow(emptyList()),
                    onError = { errors += it },
                    onSaved = {},
                    onBlockedClose = {},
                )
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.RestoreSubtask(TaskId("c1")))
            runCurrent()

            assertEquals(
                listOf("Restore failed"),
                errors,
                "a task that was never deleted cannot be restored",
            )
        }

    @Test
    fun `the undo puts the checklist item back with the row it had`() =
        runTest {
            val fakes = SlotFakes()
            val item =
                ChecklistItem(
                    id =
                        com.singularity.todo.feature.checklist.ChecklistItemId
                            .generate(),
                    taskId = "t1",
                    title = "Buy milk",
                    sortOrder = 2,
                    createdAt = 1_700_000_000_000L,
                    rowVersion = 3,
                )
            fakes.checklistRepo.seed(item)
            val source = TaskSource(task("t1"))
            val slot = slot(fakes, source, backgroundScope)
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.DeleteChecklistItem(item.id))
            runCurrent()
            slot.onIntent(TaskDetailIntent.Domain.RestoreChecklistItem(item))
            runCurrent()

            val restored = fakes.checklistRepo.items.value[item.id.value]
            assertNotNull(restored)
            assertEquals(1_700_000_000_000L, restored.createdAt)
            assertEquals(3, restored.rowVersion)
            assertEquals(2, restored.sortOrder)
        }

    // ─── The blocked-completion guard ─────────────────────────────────────────

    @Test
    fun `a subtask with an unfinished prerequisite is not closed and asks instead`() =
        runTest {
            val fakes = SlotFakes()
            val dep = task("dep", "Dependency")
            val child = task("c1", "Child").copy(parentTaskId = TaskId("t1"), blockedBy = setOf(TaskId("dep")))
            fakes.taskRepo.seed(task("t1"), dep, child)
            val source = TaskSource(task("t1"))
            val blocked = mutableListOf<BlockedCloseRequest>()
            val slot =
                slot(
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
                fakes.taskRepo.tasks.value["c1"]
                    ?.completedAt,
                "a blocked subtask must not be closed on the first request",
            )
            assertEquals(1, blocked.size, "the user must be asked")
            assertEquals(listOf("Dependency"), blocked.single().blockerTitles)
        }

    @Test
    fun `confirming the request closes the subtask`() =
        runTest {
            val fakes = SlotFakes()
            val dep = task("dep", "Dependency")
            val child = task("c1", "Child").copy(parentTaskId = TaskId("t1"), blockedBy = setOf(TaskId("dep")))
            fakes.taskRepo.seed(task("t1"), dep, child)
            val source = TaskSource(task("t1"))
            val blocked = mutableListOf<BlockedCloseRequest>()
            val slot =
                slot(
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

            assertNotNull(
                fakes.taskRepo.tasks.value["c1"]
                    ?.completedAt,
            )
        }

    @Test
    fun `an unblocked subtask closes without a question`() =
        runTest {
            val fakes = SlotFakes()
            val child = task("c1", "Child").copy(parentTaskId = TaskId("t1"))
            fakes.taskRepo.seed(task("t1"), child)
            val source = TaskSource(task("t1"))
            val blocked = mutableListOf<BlockedCloseRequest>()
            val slot =
                slot(
                    fakes,
                    source,
                    backgroundScope,
                    allTasks = listOf(child),
                    onBlockedClose = { blocked += it },
                )
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.ToggleSubtask(child))
            runCurrent()

            assertNotNull(
                fakes.taskRepo.tasks.value["c1"]
                    ?.completedAt,
            )
            assertTrue(blocked.isEmpty(), "an unblocked subtask must not raise a confirmation")
        }
}
