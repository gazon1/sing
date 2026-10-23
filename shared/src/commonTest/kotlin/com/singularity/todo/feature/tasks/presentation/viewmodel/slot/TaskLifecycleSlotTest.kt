@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers delete-with-undo, archive-without-undo, and restore. */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class TaskLifecycleSlotTest {

    private fun slot(
        fakes: SlotFakes,
        source: TaskSource,
        scope: CoroutineScope,
        onUndo: (String) -> Unit = {},
        onBack: () -> Unit = {},
    ) = TaskLifecycleSlot(
        core = fakes.core(),
        scheduling = fakes.scheduling(),
        scope = testSlotScope(scope),
        taskFlow = source.state,
        onError = {},
        onUndoDelete = { onUndo(it.id.value) },
        onUndoDeleteSeries = { ids -> onUndo(ids.joinToString(",") { id -> id.value }) },
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
    fun `deleting a series archives every occurrence and offers the undo`() = runTest {
        val fakes = SlotFakes()
        val series = TaskSeriesId("s1")
        fakes.taskRepo.seed(task("t1", seriesId = series), task("t2", seriesId = series))
        val undone = mutableListOf<String>()
        val slot = slot(fakes, TaskSource(task("t1", seriesId = series)), backgroundScope, onUndo = { undone += it })
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.DeleteSeries)
        runCurrent()

        assertNotNull(fakes.taskRepo.tasks.value["t1"]?.archivedAt)
        assertNotNull(fakes.taskRepo.tasks.value["t2"]?.archivedAt)
        assertEquals(listOf("t1,t2"), undone, "the undo has to know what went away")
    }

    @Test
    fun `deleting a series keeps the screen until the offer is declined`() = runTest {
        val fakes = SlotFakes()
        val series = TaskSeriesId("s1")
        fakes.taskRepo.seed(task("t1", seriesId = series), task("t2", seriesId = series))
        var navigated = false
        val slot = slot(
            fakes,
            TaskSource(task("t1", seriesId = series)),
            backgroundScope,
            onBack = { navigated = true },
        )
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.DeleteSeries)
        runCurrent()

        assertTrue(
            !navigated,
            "the toast that carries the way back lives in this screen — leaving first would " +
                "destroy the offer it was supposed to accompany",
        )
    }

    @Test
    fun `restoring a series puts every occurrence back`() = runTest {
        val fakes = SlotFakes()
        val series = TaskSeriesId("s1")
        fakes.taskRepo.seed(task("t1", seriesId = series), task("t2", seriesId = series))
        val slot = slot(fakes, TaskSource(task("t1", seriesId = series)), backgroundScope)
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.DeleteSeries)
        runCurrent()
        slot.onIntent(
            TaskDetailIntent.Domain.RestoreSeries(listOf(TaskId("t1"), TaskId("t2"))),
        )
        runCurrent()

        assertNull(fakes.taskRepo.tasks.value["t1"]?.archivedAt)
        assertNull(fakes.taskRepo.tasks.value["t2"]?.archivedAt)
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

    @Test
    fun `restore failure keeps the snapshot alive so the user can retry`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1"))
        val errors = mutableListOf<String>()
        val slot = TaskLifecycleSlot(
            core = fakes.core(),
            scheduling = fakes.scheduling(),
            scope = testSlotScope(backgroundScope),
            taskFlow = TaskSource(task("t1")).also { it.emit(task("t1")) }.state,
            onError = { errors += it },
            onUndoDelete = {},
            onUndoDeleteSeries = {},
            onNavigateBack = {},
            onSaved = {},
        )
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.Delete)
        runCurrent()
        assertNotNull(slot.state.value.recentlyDeleted)

        // Simulate a persistent server error
        fakes.taskRepo.restoreOverride = Result.failure(IllegalStateException("Server error"))
        slot.onIntent(TaskDetailIntent.Domain.Restore)
        runCurrent()

        // Snapshot must stay alive so the user can try again once the server recovers
        assertNotNull(slot.state.value.recentlyDeleted)
        assertEquals(listOf("Restore failed"), errors)
    }
}
