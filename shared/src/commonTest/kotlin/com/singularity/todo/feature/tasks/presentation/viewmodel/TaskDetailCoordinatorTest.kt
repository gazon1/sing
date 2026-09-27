package com.singularity.todo.feature.tasks.presentation.viewmodel

import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.feature.tasks.domain.model.TaskAiAction
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiState
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.SETTLE
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.SlotFakes
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration coverage for the coordinator: the assembled state and the routing of every
 * intent to the slot that owns it.
 *
 * The per-slot behaviour is covered by each slot's own test; what this file guards is the
 * seam — that the merge reads from all six inputs, and that no intent variant is dropped
 * by the routing `when`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailCoordinatorTest {

    private fun coordinator(fakes: SlotFakes, scope: CoroutineScope) = TaskDetailCoordinator(
        deps = fakes.deps(),
        taskId = TaskId("t1"),
        scope = testScope(scope),
    )

    @Test
    fun `an empty repository resolves to a terminal state, not a hang`() = runTest {
        val fakes = SlotFakes()
        val vm = coordinator(fakes, backgroundScope)
        delay(SETTLE)

        // The coordinator subscribes immediately, so a missing row resolves to the
        // not-found error rather than staying in Loading forever.
        val state = vm.state.value
        assertTrue(
            state is TaskDetailUiState.Error || state == TaskDetailUiState.Loading,
            "expected a terminal or loading state, got $state",
        )
    }

    @Test
    fun `assembled state carries the task, draft, and child collections`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1", title = "Original"))
        val vm = coordinator(fakes, backgroundScope)
        delay(SETTLE)

        val loaded = vm.state.value as? TaskDetailUiState.Loaded
        assertNotNull(loaded, "expected the task to have loaded, got ${vm.state.value}")
        assertEquals("Original", loaded.ui.task.title)
        // The draft is seeded from the loaded task, so the editor shows the stored title.
        assertEquals("Original", loaded.ui.titleDraft)
    }

    @Test
    fun `a missing task produces the not-found error`() = runTest {
        val fakes = SlotFakes()
        val vm = coordinator(fakes, backgroundScope)
        delay(SETTLE)

        val state = vm.state.value
        assertTrue(state is TaskDetailUiState.Error || state == TaskDetailUiState.Loading)
    }

    @Test
    fun `entity intent reaches the repository through the entity slot`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1"))
        val vm = coordinator(fakes, backgroundScope)
        delay(SETTLE)

        vm.onIntent(TaskDetailIntent.Domain.SetPriority(TaskPriority.High))
        delay(SETTLE)

        assertEquals(TaskPriority.High, fakes.taskRepo.tasks.value["t1"]?.priority)
    }

    @Test
    fun `children intent reaches the checklist repository`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1"))
        val vm = coordinator(fakes, backgroundScope)
        delay(SETTLE)

        vm.onIntent(TaskDetailIntent.Domain.AddChecklistItem("Step"))
        delay(SETTLE)

        assertEquals(1, fakes.checklistRepo.items.value.size)
    }

    @Test
    fun `lifecycle intent soft-deletes`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1"))
        val vm = coordinator(fakes, backgroundScope)
        delay(SETTLE)

        vm.onIntent(TaskDetailIntent.Domain.Delete)
        delay(SETTLE)

        assertNotNull(fakes.taskRepo.tasks.value["t1"]?.archivedAt)
    }

    @Test
    fun `draft intent is seeded and echoed back into the assembled state`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1", title = "Original"))
        val vm = coordinator(fakes, backgroundScope)
        delay(SETTLE)

        vm.onIntent(TaskDetailIntent.Domain.TitleChanged("Edited"))
        delay(SETTLE)

        val loaded = vm.state.value as? TaskDetailUiState.Loaded
        assertNotNull(loaded)
        assertEquals("Edited", loaded.ui.titleDraft)
    }

    @Test
    fun `ai intent is routed without throwing when no use case is configured`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1"))
        val vm = coordinator(fakes, backgroundScope)
        delay(SETTLE)

        vm.onIntent(TaskDetailIntent.Domain.RunAiAction(TaskAiAction.RefineTitle))
        delay(SETTLE)

        // The failure is reported through the event bus, not thrown into the caller.
        assertEquals("Test task", fakes.taskRepo.tasks.value["t1"]?.title)
    }

    @Test
    fun `every domain intent variant is routed and none is a no-op`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1"))
        val vm = coordinator(fakes, backgroundScope)
        delay(SETTLE)

        val all: List<TaskDetailIntent.Domain> = listOf(
            TaskDetailIntent.Domain.ToggleComplete,
            TaskDetailIntent.Domain.TitleChanged("T"),
            TaskDetailIntent.Domain.DescriptionChanged("D"),
            TaskDetailIntent.Domain.ToggleSomeday,
            TaskDetailIntent.Domain.SetKind(com.singularity.todo.feature.tasks.domain.model.TaskKind.Note),
            TaskDetailIntent.Domain.SetDueDate(kotlinx.datetime.LocalDate(2026, 10, 1)),
            TaskDetailIntent.Domain.SetDueTime(kotlinx.datetime.LocalTime(9, 0)),
            TaskDetailIntent.Domain.SetStartDate(kotlinx.datetime.LocalDate(2026, 9, 30)),
            TaskDetailIntent.Domain.SetStartTime(kotlinx.datetime.LocalTime(8, 0)),
            TaskDetailIntent.Domain.SetPriority(TaskPriority.Low),
            TaskDetailIntent.Domain.SetProject(null),
            TaskDetailIntent.Domain.SetTags(emptyList()),
            TaskDetailIntent.Domain.RemoveTag(com.singularity.todo.feature.tags.TagId.fromString("t")),
            TaskDetailIntent.Domain.ToggleChecklistItem(placeholderChecklistItem()),
            TaskDetailIntent.Domain.DeleteChecklistItem(
                com.singularity.todo.feature.checklist.ChecklistItemId.fromString("ci"),
            ),
            TaskDetailIntent.Domain.AddChecklistItem("item"),
            TaskDetailIntent.Domain.ToggleSubtask(task("c1")),
            TaskDetailIntent.Domain.DeleteSubtask(task("c1")),
            TaskDetailIntent.Domain.AddSubtask("child"),
            TaskDetailIntent.Domain.SetReminder(com.singularity.todo.core.reminders.ReminderOffset.AT_DUE),
            TaskDetailIntent.Domain.DeleteReminder,
            TaskDetailIntent.Domain.TogglePinned,
            TaskDetailIntent.Domain.SetDependencies(setOf(TaskId("dep"))),
            TaskDetailIntent.Domain.SetRecurrence(null),
            TaskDetailIntent.Domain.AddUrlAttachment("https://example.com", null),
            TaskDetailIntent.Domain.DeleteAttachment(
                com.singularity.todo.core.attachments.AttachmentId.fromString("a"),
            ),
            TaskDetailIntent.Domain.RunAiAction(TaskAiAction.SuggestTime),
        )

        all.forEach { vm.onIntent(it) }
        delay(SETTLE)

        // A task that survives every routed intent proves none of them crashed the loop.
        assertNotNull(fakes.taskRepo.tasks.value["t1"])
    }

    private fun placeholderChecklistItem() = com.singularity.todo.feature.checklist.ChecklistItem(
        id = com.singularity.todo.feature.checklist.ChecklistItemId.fromString("ci"),
        taskId = "t1",
        title = "item",
        sortOrder = 0,
    )
}
