package com.singularity.todo.test.fakes

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * A [ReminderScheduler] that records instead of arming anything.
 *
 * ## Why recording matters more than the return value
 *
 * Every other fake here answers "did the write happen?" by storing the row. That is not
 * enough for the scheduler, because writing a reminder's new `fireAt` and *arming the alarm*
 * are two separate acts and only one of them is observable in the database. A fake that
 * merely accepted the upsert would let a regression that stops rescheduling pass every test
 * that only inspects the table — which is exactly the shape of bug it exists to catch.
 *
 * So `scheduled` and `cancelled` are the assertions. A test that wants to know whether a
 * reminder actually re-armed can ask; one that only cares about the row can ignore them.
 *
 * Deliberately capable of failing: [failOnSchedule] makes the scheduler throw, so the call
 * path that has to cope with an alarm it could not arm is reachable without a real OS.
 */
open class FakeReminderScheduler(
    /** When true, [schedule] throws. Used to exercise the "moved but could not re-arm" path. */
    private val failOnSchedule: Boolean = false,
) : ReminderScheduler {

    /** Every alarm this scheduler has been asked to arm, in order. */
    val scheduled = mutableListOf<Reminder>()

    /** Every alarm this scheduler has been asked to drop, in order. */
    val cancelled = mutableListOf<ReminderId>()

    /** Reminders currently believed armed, keyed the way the real scheduler keys them. */
    private val armed = linkedMapOf<String, Reminder>()

    override suspend fun schedule(reminder: Reminder) {
        if (failOnSchedule) throw IllegalStateException("alarm could not be armed (test)")
        scheduled += reminder
        armed[armKey(reminder.id, reminder.userId)] = reminder
    }

    override suspend fun cancel(id: ReminderId, userId: UserId) {
        cancelled += id
        armed.remove(armKey(id, userId))
    }

    override suspend fun cancelByTask(taskId: TaskId, userId: UserId) {
        cancelled += armed.values
            .filter { it.taskId == taskId && it.userId == userId }
            .map { it.id }
        armed.entries.removeAll { it.value.taskId == taskId && it.value.userId == userId }
    }

    /** The alarm believed armed for [id], or null. The question the table cannot answer. */
    fun armedFor(id: ReminderId, userId: UserId): Reminder? = armed[armKey(id, userId)]

    private fun armKey(id: ReminderId, userId: UserId) = "reminder:${userId.value}:${id.value}"
}