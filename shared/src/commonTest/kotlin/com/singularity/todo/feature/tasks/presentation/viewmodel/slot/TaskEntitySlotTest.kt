@file:Suppress("NoDirectClockSystem")

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Covers the entity slot: the derived project/tag/available state, and the write-through
 * mutations that used to share one 30-branch `when` with everything else on the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class TaskEntitySlotTest {

    @Test
    fun `state is empty before a task arrives`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource()
        val slot = TaskEntitySlot(
            taskId = TaskId("t1"),
            core = fakes.core(),
            children = fakes.children(),
            scope = testSlotScope(backgroundScope),
            taskFlow = source.state,
        ) {}

        assertEquals(TaskEntityState(), slot.state.value)
    }

    @Test
    fun `available tasks exclude the task itself and trashed tasks`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(
            task("t1"),
            task("t2"),
            // archivedAt != null is what isTrashed derives from
            task("t3", title = "archived").copy(archivedAt = Clock.System.now()),
        )
        val source = TaskSource(task("t1"))
        val slot = TaskEntitySlot(
            taskId = TaskId("t1"),
            core = fakes.core(),
            children = fakes.children(),
            scope = testSlotScope(backgroundScope),
            taskFlow = source.state,
        ) {}
        runCurrent()

        val available = slot.state.value.availableTasks.map { it.id.value }
        assertTrue("t1" !in available, "the task must not be offered as its own dependency")
        assertTrue("t3" !in available, "trashed tasks must not be offered")
        assertTrue("t2" in available)
    }

    @Test
    fun `tags are filtered to those on the task`() = runTest {
        val fakes = SlotFakes()
        val tagA = com.singularity.todo.feature.tags.TagId.fromString("tag-a")
        val tagB = com.singularity.todo.feature.tags.TagId.fromString("tag-b")
        fakes.taskRepo.seed(task("t1").copy(tags = listOf(tagA)))
        fakes.tagsRepo.seed(tag(tagA, "A"), tag(tagB, "B"))
        val slot = TaskEntitySlot(
            taskId = TaskId("t1"),
            core = fakes.core(),
            children = fakes.children(),
            scope = testSlotScope(backgroundScope),
            // The slot filters the catalogue against the task it is handed, so the task
            // in the flow — not the seeded row — is what decides the result.
            taskFlow = TaskSource(task("t1").copy(tags = listOf(tagA))).state,
            onError = {},
        )
        runCurrent()

        // Both tags exist in the catalogue, but only tag-a is on the task.
        assertEquals(listOf(tagA), slot.state.value.tags.map { it.id })
    }

    @Test
    fun `set priority writes through to the repository`() = runTest {
        val fakes = SlotFakes()
        // The slot writes through to `update`, which production rejects for a row that
        // does not exist — so the task has to be seeded, as it would be in the app.
        fakes.taskRepo.seed(task("t1"))
        val source = TaskSource(task("t1"))
        val slot = TaskEntitySlot(
            taskId = TaskId("t1"),
            core = fakes.core(),
            children = fakes.children(),
            scope = testSlotScope(backgroundScope),
            taskFlow = source.state,
        ) {}
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.SetPriority(TaskPriority.High))
        runCurrent()

        assertEquals(TaskPriority.High, fakes.taskRepo.tasks.value["t1"]?.priority)
    }

    @Test
    fun `toggle pinned flips the flag`() = runTest {
        val fakes = SlotFakes()
        // The slot writes through to `update`, which production rejects for a row that
        // does not exist — so the task has to be seeded, as it would be in the app.
        fakes.taskRepo.seed(task("t1"))
        val source = TaskSource(task("t1"))
        val slot = TaskEntitySlot(
            taskId = TaskId("t1"),
            core = fakes.core(),
            children = fakes.children(),
            scope = testSlotScope(backgroundScope),
            taskFlow = source.state,
        ) {}
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.TogglePinned)
        runCurrent()
        // Room re-emits the updated row; the slot reads its write base from the task flow,
        // so a test must mirror that or the second toggle would flip the same stale value.
        source.emit(fakes.taskRepo.tasks.value["t1"])
        assertEquals(true, fakes.taskRepo.tasks.value["t1"]?.isPinned)

        slot.onIntent(TaskDetailIntent.Domain.TogglePinned)
        runCurrent()
        assertEquals(false, fakes.taskRepo.tasks.value["t1"]?.isPinned)
    }

    @Test
    fun `clear due date writes null`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(task("t1").copy(dueDate = kotlinx.datetime.LocalDate(2026, 9, 27)))
        val slot = TaskEntitySlot(
            taskId = TaskId("t1"),
            core = fakes.core(),
            children = fakes.children(),
            scope = testSlotScope(backgroundScope),
            taskFlow = source.state,
        ) {}
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.SetDueDate(null))
        runCurrent()

        assertNull(fakes.taskRepo.tasks.value["t1"]?.dueDate)
    }

    @Test
    fun `intent before a task arrives is ignored rather than crashing`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(null)
        val slot = TaskEntitySlot(
            taskId = TaskId("t1"),
            core = fakes.core(),
            children = fakes.children(),
            scope = testSlotScope(backgroundScope),
            taskFlow = source.state,
        ) {}
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.SetPriority(TaskPriority.Low))
        runCurrent()

        assertTrue(fakes.taskRepo.tasks.value.isEmpty())
    }

    @Test
    fun `project resolves from the task's project id`() = runTest {
        val fakes = SlotFakes()
        val projectId = ProjectId.fromString("p1")
        fakes.projectsRepo.seed(
            Project(
                id = projectId,
                name = "Work",
                color = 0xFF0000FF.toInt(),
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
                userId = TEST_USER,
            ),
        )
        val source = TaskSource(task("t1").copy(projectId = projectId))
        val slot = TaskEntitySlot(
            taskId = TaskId("t1"),
            core = fakes.core(),
            children = fakes.children(),
            scope = testSlotScope(backgroundScope),
            taskFlow = source.state,
        ) {}
        runCurrent()

        assertNotNull(slot.state.value.project)
        assertEquals("Work", slot.state.value.project?.name)
    }
}
