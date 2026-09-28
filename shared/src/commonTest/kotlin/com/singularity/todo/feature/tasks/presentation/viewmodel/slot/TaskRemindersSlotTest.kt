package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the reminders slot.
 *
 * The important property is the write order: a reminder is a database row *and* a platform
 * alarm, and the two must be written row-then-alarm so an interruption leaves a harmless
 * unscheduled row rather than an alarm with nothing behind it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskRemindersSlotTest {

    private fun slot(fakes: SlotFakes, source: TaskSource, scope: CoroutineScope) = TaskRemindersSlot(
        taskId = TaskId("t1"),
        deps = fakes.deps(),
        scope = testSlotScope(scope),
        taskFlow = source.state,
        onError = {},
    )

    @Test
    fun `no reminders before anything is scheduled`() = runTest {
        val fakes = SlotFakes()
        val slot = slot(fakes, TaskSource(task("t1")), backgroundScope)
        runCurrent()

        assertEquals(emptyList(), slot.state.value.reminders)
    }

    @Test
    fun `setting a reminder writes the row and schedules the alarm`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(
            task("t1").copy(dueDate = DUE, dueTime = AT_NINE),
        )
        val slot = slot(fakes, source, backgroundScope)
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.SetReminder(ReminderOffset.FIFTEEN_MIN))
        runCurrent()

        assertEquals(1, fakes.scheduler.scheduled.size, "the platform alarm must be scheduled")
        assertTrue(fakes.scheduler.scheduled.first().fireAt > 0)
    }

    @Test
    fun `AT_DUE clears the reminder rather than scheduling one`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(task("t1").copy(dueDate = DUE))
        val slot = slot(fakes, source, backgroundScope)
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.SetReminder(ReminderOffset.AT_DUE))
        runCurrent()

        assertEquals(listOf(TaskId("t1")), fakes.scheduler.cancelledTasks)
        assertTrue(fakes.scheduler.scheduled.isEmpty(), "AT_DUE must not schedule a new alarm")
    }

    @Test
    fun `deleting a reminder cancels the alarm and the row`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(task("t1").copy(dueDate = DUE))
        val slot = slot(fakes, source, backgroundScope)
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.DeleteReminder)
        runCurrent()

        assertEquals(listOf(TaskId("t1")), fakes.scheduler.cancelledTasks)
    }
}

private val DUE = kotlinx.datetime.LocalDate(2026, 10, 1)
private val AT_NINE = kotlinx.datetime.LocalTime(9, 0)
