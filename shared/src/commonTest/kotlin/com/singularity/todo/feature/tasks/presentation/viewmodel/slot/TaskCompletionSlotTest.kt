
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

/**
 * Covers the completion slot: the recurring branch that never stamps completedAt, and the
 * blocked-completion guard that asks before it writes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class TaskCompletionSlotTest {

    /** The blocker requests the slot raised, in order. */
    private class BlockedRecorder {
        val requests = mutableListOf<BlockedCloseRequest>()
        fun record(request: BlockedCloseRequest) {
            requests += request
        }
    }

    private fun slot(
        fakes: SlotFakes,
        source: TaskSource,
        scope: kotlinx.coroutines.CoroutineScope,
        allTasks: List<Task> = emptyList(),
        blocked: BlockedRecorder = BlockedRecorder(),
    ): TaskCompletionSlot = TaskCompletionSlot(
        core = fakes.core(),
        context = fakes.context(),
        scope = testSlotScope(scope),
        taskFlow = source.state,
        allTasks = MutableStateFlow(allTasks),
        onError = {},
        onSaved = {},
        onBlockedClose = blocked::record,
    )

    @Test
    fun `state reflects the task`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(task("t1").copy(completedAt = kotlin.time.Clock.System.now()))
        val slot = slot(fakes, source, backgroundScope)
        runCurrent()

        assertTrue(slot.state.value.isCompleted)
        assertFalse(slot.state.value.hasRecurrence)
    }

    @Test
    fun `toggle complete stamps completedAt on a plain task`() = runTest {
        val fakes = SlotFakes()
        // The slot writes through to `update`, which production rejects for a row that
        // does not exist — so the task has to be seeded, as it would be in the app.
        fakes.taskRepo.seed(task("t1"))
        val source = TaskSource(task("t1"))
        val slot = slot(fakes, source, backgroundScope)
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.ToggleComplete)
        runCurrent()

        assertNotNull(fakes.taskRepo.tasks.value["t1"]?.completedAt)
    }

    @Test
    fun `toggle complete clears completedAt when already done`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(task("t1").copy(completedAt = kotlin.time.Clock.System.now()))
        val slot = slot(fakes, source, backgroundScope)
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.ToggleComplete)
        runCurrent()

        assertNull(fakes.taskRepo.tasks.value["t1"]?.completedAt)
    }

    @Test
    fun `completing a recurring task goes through the recurrence use case`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(
            task("t1").copy(
                recurrence = com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval(
                    base = com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase.FROM_DUE,
                    amount = 1,
                    unit = kotlinx.datetime.DateTimeUnit.DAY,
                ),
            ),
        )
        val slot = slot(fakes, source, backgroundScope)
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.ToggleComplete)
        runCurrent()

        assertEquals(TaskId("t1"), fakes.recurringCompleted)
        assertNull(
            fakes.taskRepo.tasks.value["t1"]?.completedAt,
            "a recurring task must not be completed in place",
        )
    }

    // ─── The blocked-completion guard ─────────────────────────────────────────

    @Test
    fun `a task with an unfinished prerequisite is not closed and asks instead`() = runTest {
        val fakes = SlotFakes()
        val dep = task("dep", "Dependency")
        val blockedTask = task("t1", "Blocked").copy(dependsOn = setOf(TaskId("dep")))
        fakes.taskRepo.seed(blockedTask)
        val source = TaskSource(blockedTask)
        val blocked = BlockedRecorder()
        val slot = slot(fakes, source, backgroundScope, allTasks = listOf(dep, blockedTask), blocked = blocked)
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.ToggleComplete)
        runCurrent()

        assertNull(fakes.taskRepo.tasks.value["t1"]?.completedAt, "nothing may be written")
        assertEquals(1, blocked.requests.size)
        assertEquals(listOf("Dependency"), blocked.requests.single().blockerTitles)
    }

    @Test
    fun `confirming the request closes the task`() = runTest {
        val fakes = SlotFakes()
        val dep = task("dep", "Dependency")
        val blockedTask = task("t1", "Blocked").copy(dependsOn = setOf(TaskId("dep")))
        fakes.taskRepo.seed(blockedTask)
        val source = TaskSource(blockedTask)
        val blocked = BlockedRecorder()
        val slot = slot(fakes, source, backgroundScope, allTasks = listOf(dep, blockedTask), blocked = blocked)
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.ToggleComplete)
        runCurrent()
        slot.onIntent(TaskDetailIntent.Domain.ConfirmBlockedClose(TaskId("t1")))
        runCurrent()

        assertNotNull(fakes.taskRepo.tasks.value["t1"]?.completedAt)
    }

    @Test
    fun `a finished prerequisite does not ask`() = runTest {
        val fakes = SlotFakes()
        val dep = task("dep", "Dependency").copy(completedAt = kotlin.time.Clock.System.now())
        val plain = task("t1", "Plain").copy(dependsOn = setOf(TaskId("dep")))
        fakes.taskRepo.seed(plain)
        val source = TaskSource(plain)
        val blocked = BlockedRecorder()
        val slot = slot(fakes, source, backgroundScope, allTasks = listOf(dep, plain), blocked = blocked)
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.ToggleComplete)
        runCurrent()

        assertNotNull(fakes.taskRepo.tasks.value["t1"]?.completedAt)
        assertTrue(blocked.requests.isEmpty(), "a finished prerequisite is not in the way")
    }

    @Test
    fun `taking a finished task back into work is never asked about`() = runTest {
        val fakes = SlotFakes()
        val dep = task("dep", "Dependency")
        val blockedTask = task("t1", "Blocked")
            .copy(dependsOn = setOf(TaskId("dep")), completedAt = kotlin.time.Clock.System.now())
        fakes.taskRepo.seed(blockedTask)
        val source = TaskSource(blockedTask)
        val blocked = BlockedRecorder()
        val slot = slot(fakes, source, backgroundScope, allTasks = listOf(dep, blockedTask), blocked = blocked)
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.ToggleComplete)
        runCurrent()

        assertNull(fakes.taskRepo.tasks.value["t1"]?.completedAt)
        assertTrue(blocked.requests.isEmpty(), "only the closing direction is gated")
    }

    @Test
    fun `a repeating task with an unfinished prerequisite asks before it rolls over`() = runTest {
        val fakes = SlotFakes()
        val dep = task("dep", "Dependency")
        val recurring = task("t1", "Repeating").copy(
            dependsOn = setOf(TaskId("dep")),
            recurrence = com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval(
                base = com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase.FROM_DUE,
                amount = 1,
                unit = kotlinx.datetime.DateTimeUnit.DAY,
            ),
        )
        fakes.taskRepo.seed(recurring)
        val source = TaskSource(recurring)
        val blocked = BlockedRecorder()
        val slot = slot(fakes, source, backgroundScope, allTasks = listOf(dep, recurring), blocked = blocked)
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.ToggleComplete)
        runCurrent()

        assertEquals(1, blocked.requests.size, "rolling a recurrence forward is still closing the task")
        assertNull(fakes.recurringCompleted, "nothing may be written before the confirmation")
    }

    @Test
    fun `confirming a repeating task rolls it over`() = runTest {
        val fakes = SlotFakes()
        val dep = task("dep", "Dependency")
        val recurring = task("t1", "Repeating").copy(
            dependsOn = setOf(TaskId("dep")),
            recurrence = com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase.FROM_DUE.let {
                com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval(
                    base = it,
                    amount = 1,
                    unit = kotlinx.datetime.DateTimeUnit.DAY,
                )
            },
        )
        fakes.taskRepo.seed(recurring)
        val source = TaskSource(recurring)
        val blocked = BlockedRecorder()
        val slot = slot(fakes, source, backgroundScope, allTasks = listOf(dep, recurring), blocked = blocked)
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.ToggleComplete)
        runCurrent()
        slot.onIntent(TaskDetailIntent.Domain.ConfirmBlockedClose(TaskId("t1")))
        runCurrent()

        assertEquals(TaskId("t1"), fakes.recurringCompleted, "the confirmed close must roll the recurrence over")
    }
}
