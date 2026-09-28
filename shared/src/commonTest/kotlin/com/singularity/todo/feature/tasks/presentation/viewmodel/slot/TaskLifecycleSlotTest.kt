package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers delete-with-undo, archive-without-undo, and restore. */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskLifecycleSlotTest {

    private fun slot(
        fakes: SlotFakes,
        source: TaskSource,
        scope: CoroutineScope,
        onUndo: (String) -> Unit = {},
        onBack: () -> Unit = {},
    ) = TaskLifecycleSlot(
        deps = fakes.deps(),
        scope = testSlotScope(scope),
        taskFlow = source.state,
        onError = {},
        onUndoDelete = { onUndo(it.id.value) },
        onNavigateBack = onBack,
        onSaved = {},
    )

    @Test
    fun `delete soft-deletes and offers undo`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1"))
        val undone = mutableListOf<String>()
        val slot = slot(fakes, TaskSource(task("t1")), backgroundScope, onUndo = { undone += it })
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.Delete)
        runCurrent()

        assertNotNull(fakes.taskRepo.tasks.value["t1"]?.archivedAt)
        assertEquals(listOf("t1"), undone)
    }

    @Test
    fun `delete keeps a snapshot for restore`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1"))
        val slot = slot(fakes, TaskSource(task("t1")), backgroundScope)
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.Delete)
        runCurrent()

        assertNotNull(slot.state.value.recentlyDeleted)
    }

    @Test
    fun `archive does not keep a snapshot`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1"))
        val slot = slot(fakes, TaskSource(task("t1")), backgroundScope)
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.Archive)
        runCurrent()

        assertNotNull(fakes.taskRepo.tasks.value["t1"]?.archivedAt)
        assertNull(slot.state.value.recentlyDeleted, "archive must not offer an undo path")
    }

    @Test
    fun `archive navigates back`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1"))
        var navigated = false
        val slot = slot(fakes, TaskSource(task("t1")), backgroundScope, onBack = { navigated = true })
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.Archive)
        runCurrent()

        assertTrue(navigated)
    }

    @Test
    fun `restore brings the task back and clears the snapshot`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1"))
        val slot = slot(fakes, TaskSource(task("t1")), backgroundScope)
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.Delete)
        runCurrent()
        slot.onIntent(TaskDetailIntent.Domain.Restore)
        runCurrent()

        assertNull(fakes.taskRepo.tasks.value["t1"]?.archivedAt)
        assertNull(slot.state.value.recentlyDeleted)
    }

    @Test
    fun `restore without a prior delete is a no-op`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1"))
        val slot = slot(fakes, TaskSource(task("t1")), backgroundScope)
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.Restore)
        runCurrent()

        assertNull(slot.state.value.recentlyDeleted)
    }
}
