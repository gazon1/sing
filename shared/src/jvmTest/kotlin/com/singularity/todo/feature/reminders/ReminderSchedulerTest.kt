package com.singularity.todo.feature.reminders

import co.touchlab.kermit.Logger
import com.singularity.todo.core.notifications.FakeNotificationPort
import com.singularity.todo.feature.tasks.TaskId
import com.singularity.todo.feature.tasks.UserId
import com.singularity.todo.test.fakes.FakeReminderRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReminderSchedulerTest {

    private val userId = UserId("test-user")
    private val testLog = Logger.withTag("ReminderSchedulerTest")

    @Test
    fun `poll fires notification for due reminder`() = runTest {
        val fakePort = FakeNotificationPort()
        val repo = FakeReminderRepository(userId)
        val scheduler = ReminderScheduler(testLog, fakePort, repo, userId)

        val reminder = Reminder(
            id = ReminderId("r1"),
            taskId = TaskId("t1"),
            userId = userId,
            type = ReminderType.Gentle,
            offsetMinutes = -15,
            fireAt = System.currentTimeMillis() - 1000,
            recurringPattern = null
        )
        repo.upsert(reminder)

        scheduler.poll()

        assertEquals(1, fakePort.scheduled.size)
        assertEquals("reminder:r1", fakePort.scheduled[0].key)
    }

    @Test
    fun `poll deletes one-shot reminder after firing`() = runTest {
        val fakePort = FakeNotificationPort()
        val repo = FakeReminderRepository(userId)
        val scheduler = ReminderScheduler(testLog, fakePort, repo, userId)

        val reminder = Reminder(
            id = ReminderId("r2"),
            taskId = TaskId("t1"),
            userId = userId,
            type = ReminderType.Gentle,
            offsetMinutes = -5,
            fireAt = System.currentTimeMillis() - 500,
            recurringPattern = null
        )
        repo.upsert(reminder)

        scheduler.poll()

        assertTrue(repo.getById(ReminderId("r2"), userId).getOrNull() == null)
    }

    @Test
    fun `poll does not delete recurring reminder`() = runTest {
        val fakePort = FakeNotificationPort()
        val repo = FakeReminderRepository(userId)
        val scheduler = ReminderScheduler(testLog, fakePort, repo, userId)

        val reminder = Reminder(
            id = ReminderId("r3"),
            taskId = TaskId("t1"),
            userId = userId,
            type = ReminderType.Gentle,
            offsetMinutes = -10,
            fireAt = System.currentTimeMillis() - 100,
            recurringPattern = "0 9 * * *"
        )
        repo.upsert(reminder)

        scheduler.poll()

        assertTrue(repo.getById(ReminderId("r3"), userId).getOrNull() != null)
    }

    @Test
    fun `poll skips future reminders`() = runTest {
        val fakePort = FakeNotificationPort()
        val repo = FakeReminderRepository(userId)
        val scheduler = ReminderScheduler(testLog, fakePort, repo, userId)

        val futureReminder = Reminder(
            id = ReminderId("r4"),
            taskId = TaskId("t1"),
            userId = userId,
            type = ReminderType.Gentle,
            offsetMinutes = 30,
            fireAt = System.currentTimeMillis() + 1_000_000,
            recurringPattern = null
        )
        repo.upsert(futureReminder)

        scheduler.poll()

        assertEquals(0, fakePort.scheduled.size)
    }
}
