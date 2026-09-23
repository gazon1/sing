package com.singularity.todo.test.fakes

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderType
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FakeReminderRepositoryTest {

    private val userId = UserId("test-user")
    private val testReminder = Reminder(
        id = ReminderId("reminder-1"),
        taskId = TaskId.generate(),
        userId = userId,
        type = ReminderType.Gentle,
        offsetMinutes = -10,
        fireAt = 0L,
        recurringPattern = null,
    )

    @Test
    fun `upsertOverride returns injected failure`() = runTest {
        val repo = FakeReminderRepository()
        repo.upsertOverride = Result.failure(IllegalStateException("injected"))

        val result = repo.upsert(testReminder)

        assertIs<IllegalStateException>(result.exceptionOrNull())
        assertEquals("injected", result.exceptionOrNull()?.message)
    }

    @Test
    fun `override cleared falls through to runCatching success`() = runTest {
        val repo = FakeReminderRepository()

        repo.upsertOverride = Result.failure(IllegalStateException("injected"))
        repo.upsertOverride = null  // clear

        val result = repo.upsert(testReminder)

        assertTrue(result.isSuccess)
    }
}
