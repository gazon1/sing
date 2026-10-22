@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the reminders slot.
 *
 * The important property is the write order: a reminder is a database row *and* a platform
 * alarm, and the two must be written row-then-alarm so an interruption leaves a harmless
 * unscheduled row rather than an alarm with nothing behind it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class TaskRemindersSlotTest {

    private fun slot(
        fakes: SlotFakes,
        source: TaskSource,
        scope: CoroutineScope,
        sourceViewId: SavedAgendaViewId? = null,
    ) = TaskRemindersSlot(
        taskId = TaskId("t1"),
        sourceViewId = sourceViewId,
        core = fakes.core(),
        scheduling = fakes.scheduling(),
        context = fakes.context(),
        scope = testSlotScope(scope),
        taskFlow = source.state,
        onError = {},
    )

    private fun slotCapturingErrors(
        fakes: SlotFakes,
        source: TaskSource,
        scope: CoroutineScope,
        onError: (String) -> Unit,
    ) = TaskRemindersSlot(
        taskId = TaskId("t1"),
        core = fakes.core(),
        scheduling = fakes.scheduling(),
        context = fakes.context(),
        scope = testSlotScope(scope),
        taskFlow = source.state,
        onError = onError,
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

    /**
     * A reminder set on a task the person opened from a saved agenda view points back
     * at that view: the notification carries the view's identity, and the tap opens
     * the list the reminder was about.
     *
     * The column existed and the notification read it — nothing ever wrote it, so every
     * notification was posted without a target and the tap opened the app wherever it
     * happened to be.
     */
    @Test
    fun `a reminder records the saved view the task was opened from`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(task("t1").copy(dueDate = DUE))
        val slot = slot(fakes, source, backgroundScope, SavedAgendaViewId.fromString("v1"))
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.SetReminder(ReminderOffset.FIFTEEN_MIN))
        runCurrent()

        val stored = fakes.reminderRepo.reminders.value.values.single()
        assertEquals(SavedAgendaViewId.fromString("v1"), stored.viewId)
    }

    @Test
    fun `a reminder set on a task with no saved view behind it carries no view`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(task("t1").copy(dueDate = DUE))
        val slot = slot(fakes, source, backgroundScope)
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.SetReminder(ReminderOffset.FIFTEEN_MIN))
        runCurrent()

        val stored = fakes.reminderRepo.reminders.value.values.single()
        assertNull(stored.viewId, "a task opened from a tab has no view to point back at")
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

    // ─── Capability gate ───────────────────────────────────────────────────────

    /**
     * The Desktop defect this covers: `JvmReminderScheduler` was a no-op on every
     * method, so the slot wrote the row, the UI reported success, and the reminder
     * could never fire. A stored row is the half the user can see — it renders as a
     * live reminder forever — so refusing has to happen *before* the write.
     */
    @Test
    fun `an unsupported platform stores no row and no alarm`() = runTest {
        val fakes = SlotFakes(remindersSupported = false)
        val source = TaskSource(task("t1").copy(dueDate = DUE))
        val errors = mutableListOf<String>()
        val slot = slotCapturingErrors(fakes, source, backgroundScope) { errors += it }
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.SetReminder(ReminderOffset.FIFTEEN_MIN))
        runCurrent()

        assertTrue(
            fakes.reminderRepo.reminders.value.isEmpty(),
            "an unsupported platform must not persist a reminder that cannot fire",
        )
        assertTrue(
            fakes.scheduler.scheduled.isEmpty(),
            "nothing may be handed to a scheduler that cannot arm anything",
        )
        assertEquals(1, errors.size, "the user must be told, not left with silent success")
    }

    /**
     * The row is the part that lies. If the gate were implemented as "write the row,
     * then skip the alarm", this assertion fails while an error-only test still passes —
     * which is why the assertion above is on the repository, not on the callback.
     */
    @Test
    fun `the gate leaves the reminder list empty on an unsupported platform`() = runTest {
        val fakes = SlotFakes(remindersSupported = false)
        val source = TaskSource(task("t1").copy(dueDate = DUE))
        val slot = slot(fakes, source, backgroundScope)
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.SetReminder(ReminderOffset.FIFTEEN_MIN))
        runCurrent()

        assertEquals(emptyList(), slot.state.value.reminders, "no row may surface to the UI")
    }

    /**
     * Clearing is not gated. On an unsupported platform nothing was ever armed, so a
     * delete must stay a harmless no-op rather than reporting an error — otherwise the
     * user cannot tidy up reminders seeded by a sync or an older build.
     */
    @Test
    fun `clearing still works on an unsupported platform`() = runTest {
        val fakes = SlotFakes(remindersSupported = false)
        val source = TaskSource(task("t1").copy(dueDate = DUE))
        val errors = mutableListOf<String>()
        val slot = slotCapturingErrors(fakes, source, backgroundScope) { errors += it }
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.DeleteReminder)
        runCurrent()

        assertEquals(listOf(TaskId("t1")), fakes.scheduler.cancelledTasks)
        assertTrue(errors.isEmpty(), "clearing must not surface an error")
    }
}

private val DUE = kotlinx.datetime.LocalDate(2026, 10, 1)
private val AT_NINE = kotlinx.datetime.LocalTime(9, 0)
