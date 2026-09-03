package com.singularity.todo.feature.reminders

import com.singularity.todo.core.notifications.FakeNotificationPort
import com.singularity.todo.feature.tasks.TaskId
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeReminderRepository(private val currentUserId: UserId = UserId("test-user")) : ReminderRepository {
    private val reminders = mutableListOf<Reminder>()

    override fun watchAll(userId: UserId): Flow<List<Reminder>> = flowOf(reminders.filter { it.userId == currentUserId })

    override fun watchByTask(taskId: TaskId, userId: UserId): Flow<List<Reminder>> =
        flowOf(reminders.filter { it.taskId == taskId && it.userId == currentUserId })

    override fun watchDueBefore(nowEpochMs: Long, userId: UserId): Flow<List<Reminder>> =
        flowOf(reminders.filter { it.fireAt <= nowEpochMs && it.userId == currentUserId })

    override suspend fun upsert(reminder: Reminder) {
        val idx = reminders.indexOfFirst { it.id == reminder.id }
        if (idx >= 0) reminders[idx] = reminder else reminders.add(reminder)
    }

    override suspend fun delete(reminderId: ReminderId, userId: UserId) {
        reminders.removeAll { it.id == reminderId && it.userId == currentUserId }
    }

    override suspend fun deleteByTask(taskId: TaskId, userId: UserId) {
        reminders.removeAll { it.taskId == taskId && it.userId == currentUserId }
    }

    override suspend fun getById(reminderId: ReminderId, userId: UserId): Reminder? =
        reminders.find { it.id == reminderId && it.userId == currentUserId }

    fun add(vararg reminder: Reminder) = reminders.addAll(reminder)
}

class ReminderSchedulerTest {

    private val userId = UserId("test-user")

    @Test
    fun `poll fires notification for due reminder`() = runTest {
        val fakePort = FakeNotificationPort()
        val repo = FakeReminderRepository(userId)
        val scheduler = ReminderScheduler(fakePort, repo, userId)

        val reminder = Reminder(
            id = ReminderId("r1"),
            taskId = TaskId("t1"),
            userId = userId,
            type = ReminderType.Gentle,
            offsetMinutes = -15,
            fireAt = System.currentTimeMillis() - 1000, // 1s ago
            recurringPattern = null
        )
        repo.add(reminder)

        scheduler.poll()

        assertEquals(1, fakePort.scheduled.size)
        assertEquals("reminder:r1", fakePort.scheduled[0].key)
    }

    @Test
    fun `poll deletes one-shot reminder after firing`() = runTest {
        val fakePort = FakeNotificationPort()
        val repo = FakeReminderRepository(userId)
        val scheduler = ReminderScheduler(fakePort, repo, userId)

        val reminder = Reminder(
            id = ReminderId("r2"),
            taskId = TaskId("t1"),
            userId = userId,
            type = ReminderType.Gentle,
            offsetMinutes = -5,
            fireAt = System.currentTimeMillis() - 500,
            recurringPattern = null
        )
        repo.add(reminder)

        scheduler.poll()

        // one-shot should be deleted
        assertTrue(repo.getById(ReminderId("r2"), userId) == null)
    }

    @Test
    fun `poll does not delete recurring reminder`() = runTest {
        val fakePort = FakeNotificationPort()
        val repo = FakeReminderRepository(userId)
        val scheduler = ReminderScheduler(fakePort, repo, userId)

        val reminder = Reminder(
            id = ReminderId("r3"),
            taskId = TaskId("t1"),
            userId = userId,
            type = ReminderType.Gentle,
            offsetMinutes = -10,
            fireAt = System.currentTimeMillis() - 100,
            recurringPattern = "0 9 * * *"
        )
        repo.add(reminder)

        scheduler.poll()

        // recurring should NOT be deleted
        assertTrue(repo.getById(ReminderId("r3"), userId) != null)
    }

    @Test
    fun `poll skips future reminders`() = runTest {
        val fakePort = FakeNotificationPort()
        val repo = FakeReminderRepository(userId)
        val scheduler = ReminderScheduler(fakePort, repo, userId)

        val futureReminder = Reminder(
            id = ReminderId("r4"),
            taskId = TaskId("t1"),
            userId = userId,
            type = ReminderType.Gentle,
            offsetMinutes = 30,
            fireAt = System.currentTimeMillis() + 1_000_000, // far future
            recurringPattern = null
        )
        repo.add(futureReminder)

        scheduler.poll()

        assertEquals(0, fakePort.scheduled.size)
    }
}
