package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers the completion slot, including the recurring branch that never stamps completedAt. */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskCompletionSlotTest {

    @Test
    fun `state reflects the task`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(task("t1").copy(completedAt = kotlin.time.Clock.System.now()))
        val slot = TaskCompletionSlot(fakes.deps(), testSlotScope(backgroundScope), source.state) {}
        delay(SETTLE)

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
        val slot = TaskCompletionSlot(fakes.deps(), testSlotScope(backgroundScope), source.state) {}
        delay(SETTLE)

        slot.onIntent(TaskDetailIntent.Domain.ToggleComplete)
        delay(SETTLE)

        assertNotNull(fakes.taskRepo.tasks.value["t1"]?.completedAt)
    }

    @Test
    fun `toggle complete clears completedAt when already done`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(task("t1").copy(completedAt = kotlin.time.Clock.System.now()))
        val slot = TaskCompletionSlot(fakes.deps(), testSlotScope(backgroundScope), source.state) {}
        delay(SETTLE)

        slot.onIntent(TaskDetailIntent.Domain.ToggleComplete)
        delay(SETTLE)

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
        val slot = TaskCompletionSlot(fakes.deps(), testSlotScope(backgroundScope), source.state) {}
        delay(SETTLE)

        slot.onIntent(TaskDetailIntent.Domain.ToggleComplete)
        delay(SETTLE)

        assertEquals(TaskId("t1"), fakes.recurringCompleted)
        assertNull(
            fakes.taskRepo.tasks.value["t1"]?.completedAt,
            "a recurring task must not be completed in place",
        )
    }
}
