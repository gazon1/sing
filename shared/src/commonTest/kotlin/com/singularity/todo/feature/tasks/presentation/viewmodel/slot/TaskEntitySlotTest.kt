
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.state.TaskDetailIntent
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
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
@Epic("Tasks")
@Feature("Presentation")
class TaskEntitySlotTest {
    @Test
    fun `state is empty before a task arrives`() =
        runTest {
            val fakes = SlotFakes()
            val source = TaskSource()
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                ) {}

            assertEquals(TaskEntityState(), slot.state.value)
        }

    @Test
    fun `related tasks resolve current relations`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(
                task("t1"),
                task("t2", title = "Dep"),
                task("t3", title = "Follow"),
            )
            val source =
                TaskSource(
                    task("t1").copy(
                        blockedBy = setOf(TaskId("t2")),
                        followsUp = setOf(TaskId("t3")),
                    ),
                )
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                ) {}
            runCurrent()

            val related =
                slot.state.value.relatedTasks
                    .map { it.id.value }
                    .toSet()
            assertEquals(setOf("t2", "t3"), related)
        }

    @Test
    fun `related tasks clear when relations are removed`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(task("t1"), task("t2", title = "Dep"))
            val source = TaskSource(task("t1").copy(blockedBy = setOf(TaskId("t2"))))
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                ) {}
            runCurrent()
            assertEquals(1, slot.state.value.relatedTasks.size)

            source.emit(task("t1"))
            runCurrent()
            assertTrue(
                slot.state.value.relatedTasks
                    .isEmpty(),
            )
        }

    @Test
    fun `tags are filtered to those on the task`() =
        runTest {
            val fakes = SlotFakes()
            val tagA =
                com.singularity.todo.feature.tags.TagId
                    .fromString("tag-a")
            val tagB =
                com.singularity.todo.feature.tags.TagId
                    .fromString("tag-b")
            fakes.taskRepo.seed(task("t1").copy(tags = listOf(tagA)))
            fakes.tagsRepo.seed(tag(tagA, "A"), tag(tagB, "B"))
            val slot =
                TaskEntitySlot(
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
            assertEquals(
                listOf(tagA),
                slot.state.value.tags
                    .map { it.id },
            )
        }

    @Test
    fun `set priority writes through to the repository`() =
        runTest {
            val fakes = SlotFakes()
            // The slot writes through to `update`, which production rejects for a row that
            // does not exist — so the task has to be seeded, as it would be in the app.
            fakes.taskRepo.seed(task("t1"))
            val source = TaskSource(task("t1"))
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                ) {}
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.SetPriority(TaskPriority.High))
            runCurrent()

            assertEquals(
                TaskPriority.High,
                fakes.taskRepo.tasks.value["t1"]
                    ?.priority,
            )
        }

    @Test
    fun `toggle pinned flips the flag`() =
        runTest {
            val fakes = SlotFakes()
            // The slot writes through to `update`, which production rejects for a row that
            // does not exist — so the task has to be seeded, as it would be in the app.
            fakes.taskRepo.seed(task("t1"))
            val source = TaskSource(task("t1"))
            val slot =
                TaskEntitySlot(
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
            assertEquals(
                true,
                fakes.taskRepo.tasks.value["t1"]
                    ?.isPinned,
            )

            slot.onIntent(TaskDetailIntent.Domain.TogglePinned)
            runCurrent()
            assertEquals(
                false,
                fakes.taskRepo.tasks.value["t1"]
                    ?.isPinned,
            )
        }

    @Test
    fun `clear due date writes null`() =
        runTest {
            val fakes = SlotFakes()
            val source = TaskSource(task("t1").copy(dueDate = kotlinx.datetime.LocalDate(2026, 9, 27)))
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                ) {}
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.SetDueDate(null))
            runCurrent()

            assertNull(
                fakes.taskRepo.tasks.value["t1"]
                    ?.dueDate,
            )
        }

    // ─── The planning facts: date, time, deadline, someday ──────────────────
    //
    // The scheduling surface keeps these four apart, and the rules about which
    // of them a change touches are the ones the ADR states. They are the rules
    // a user cannot see, so they are the rules worth a test.

    @Test
    fun `clearing the due date clears the time with it and keeps the deadline`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(
                task("t1").copy(
                    dueDate = LocalDate(2026, 9, 27),
                    dueTime = LocalTime(14, 30),
                    endDate = LocalDate(2026, 9, 30),
                ),
            )
            val source =
                TaskSource(
                    task("t1").copy(
                        dueDate = LocalDate(2026, 9, 27),
                        dueTime = LocalTime(14, 30),
                        endDate = LocalDate(2026, 9, 30),
                    ),
                )
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                ) {}
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.SetDueDate(null))
            runCurrent()

            val written = fakes.taskRepo.tasks.value["t1"]
            assertNull(written?.dueDate, "the date must be gone")
            assertNull(
                written?.dueTime,
                "a time without a date is not a moment — it goes with the date",
            )
            assertEquals(
                LocalDate(2026, 9, 30),
                written?.endDate,
                "the deadline is a bound of its own — clearing the date must not erase it",
            )
        }

    @Test
    fun `setting a date takes the task out of the someday bucket`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(task("t1").copy(someday = true))
            val source = TaskSource(task("t1").copy(someday = true))
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                ) {}
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.SetDueDate(LocalDate(2026, 9, 27)))
            runCurrent()

            assertEquals(
                false,
                fakes.taskRepo.tasks.value["t1"]
                    ?.someday,
                "a dated task is not a deferred one",
            )
        }

    @Test
    fun `the someday preset clears the date and the time and keeps the deadline`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(
                task("t1").copy(
                    dueDate = LocalDate(2026, 9, 27),
                    dueTime = LocalTime(14, 30),
                    endDate = LocalDate(2026, 9, 30),
                ),
            )
            val source =
                TaskSource(
                    task("t1").copy(
                        dueDate = LocalDate(2026, 9, 27),
                        dueTime = LocalTime(14, 30),
                        endDate = LocalDate(2026, 9, 30),
                    ),
                )
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                ) {}
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.SetSomeday(true))
            runCurrent()

            val written = fakes.taskRepo.tasks.value["t1"]
            assertEquals(true, written?.someday)
            assertNull(written?.dueDate, "`someday` means no date")
            assertNull(written?.dueTime)
            assertEquals(
                LocalDate(2026, 9, 30),
                written?.endDate,
                "the deadline is not the date — it stays",
            )
        }

    @Test
    fun `the deadline is written on its own field`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(task("t1"))
            val source = TaskSource(task("t1"))
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                ) {}
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.SetDeadline(LocalDate(2026, 9, 30)))
            runCurrent()

            assertEquals(
                LocalDate(2026, 9, 30),
                fakes.taskRepo.tasks.value["t1"]
                    ?.endDate,
            )
        }

    @Test
    fun `clearing the deadline leaves the due date alone`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(task("t1").copy(dueDate = LocalDate(2026, 9, 27)))
            val source = TaskSource(task("t1").copy(dueDate = LocalDate(2026, 9, 27)))
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                ) {}
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.SetDeadline(null))
            runCurrent()

            val written = fakes.taskRepo.tasks.value["t1"]
            assertNull(written?.endDate)
            assertEquals(
                LocalDate(2026, 9, 27),
                written?.dueDate,
                "the deadline and the due date are facts of their own",
            )
        }

    @Test
    fun `intent before a task arrives is ignored rather than crashing`() =
        runTest {
            val fakes = SlotFakes()
            val source = TaskSource(null)
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                ) {}
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.SetPriority(TaskPriority.Low))
            runCurrent()

            assertTrue(
                fakes.taskRepo.tasks.value
                    .isEmpty(),
            )
        }

    // ─── The undo of a removed tag ────────────────────────────────────────────

    @Test
    fun `removing a tag offers an undo`() =
        runTest {
            val fakes = SlotFakes()
            val tagA =
                com.singularity.todo.feature.tags.TagId
                    .fromString("tag-a")
            fakes.taskRepo.seed(task("t1").copy(tags = listOf(tagA)))
            fakes.tagsRepo.seed(tag(tagA, "A"))
            val undone = mutableListOf<com.singularity.todo.feature.tags.Tag>()
            val source = TaskSource(task("t1").copy(tags = listOf(tagA)))
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                    onUndoRemoveTag = { undone += it },
                    onError = {},
                )
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.RemoveTag(tagA))
            runCurrent()

            assertEquals(1, undone.size, "the user must be offered a way back")
            assertEquals("A", undone.single().name)
            assertTrue(
                fakes.taskRepo.tasks.value["t1"]
                    ?.tags
                    ?.isEmpty() == true,
                "the tag must be off the task",
            )
        }

    @Test
    fun `the undo puts the tag back`() =
        runTest {
            val fakes = SlotFakes()
            val tagA =
                com.singularity.todo.feature.tags.TagId
                    .fromString("tag-a")
            fakes.taskRepo.seed(task("t1").copy(tags = listOf(tagA)))
            fakes.tagsRepo.seed(tag(tagA, "A"))
            val source = TaskSource(task("t1").copy(tags = listOf(tagA)))
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                    onError = {},
                )
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.RemoveTag(tagA))
            runCurrent()
            slot.onIntent(TaskDetailIntent.Domain.RestoreTag(tagA))
            runCurrent()

            assertEquals(
                listOf(tagA),
                fakes.taskRepo.tasks.value["t1"]
                    ?.tags,
                "the tag must come back — once",
            )
        }

    @Test
    fun `removing a tag writes the tags the task carries now`() =
        runTest {
            val fakes = SlotFakes()
            val tagA =
                com.singularity.todo.feature.tags.TagId
                    .fromString("tag-a")
            val tagB =
                com.singularity.todo.feature.tags.TagId
                    .fromString("tag-b")
            // The repository knows about both tags; the screen has not caught up yet.
            fakes.taskRepo.seed(task("t1").copy(tags = listOf(tagA, tagB)))
            fakes.tagsRepo.seed(tag(tagA, "A"), tag(tagB, "B"))
            val source = TaskSource(task("t1").copy(tags = listOf(tagA)))
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                    onError = {},
                )
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.RemoveTag(tagA))
            runCurrent()

            assertEquals(
                listOf(tagB),
                fakes.taskRepo.tasks.value["t1"]
                    ?.tags,
                "a tag added after the snapshot must survive the removal",
            )
        }

    @Test
    fun `the undo of a tag that is already there writes nothing`() =
        runTest {
            val fakes = SlotFakes()
            val tagA =
                com.singularity.todo.feature.tags.TagId
                    .fromString("tag-a")
            fakes.taskRepo.seed(task("t1").copy(tags = listOf(tagA)))
            fakes.tagsRepo.seed(tag(tagA, "A"))
            val source = TaskSource(task("t1").copy(tags = listOf(tagA)))
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                    onError = {},
                )
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.RestoreTag(tagA))
            runCurrent()

            assertEquals(
                listOf(tagA),
                fakes.taskRepo.tasks.value["t1"]
                    ?.tags,
                "a tag must not be put on the task twice",
            )
        }

    @Test
    fun `the undo does not lift the AI suppression of the tag`() =
        runTest {
            val fakes = SlotFakes()
            val tagA =
                com.singularity.todo.feature.tags.TagId
                    .fromString("tag-a")
            fakes.taskRepo.seed(task("t1").copy(tags = listOf(tagA)))
            fakes.tagsRepo.seed(tag(tagA, "A"))
            val source = TaskSource(task("t1").copy(tags = listOf(tagA)))
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                    onError = {},
                )
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.RemoveTag(tagA))
            runCurrent()
            slot.onIntent(TaskDetailIntent.Domain.RestoreTag(tagA))
            runCurrent()

            assertEquals(
                setOf(tagA),
                fakes.taskRepo.tasks.value["t1"]
                    ?.aiSuppressedTagIds,
                "the AI must not start proposing the tag the user took off",
            )
        }

    @Test
    fun `project resolves from the task's project id`() =
        runTest {
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
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                ) {}
            runCurrent()

            assertNotNull(slot.state.value.project)
            assertEquals(
                "Work",
                slot.state.value.project
                    ?.name,
            )
        }
}
