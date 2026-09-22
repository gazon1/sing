package com.singularity.todo.feature.reminders

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReminderFireLogicTest {

    private fun makeReminder(
        id: ReminderId = ReminderId.generate(),
        recurringPattern: String? = null,
    ) = Reminder(
        id = id,
        taskId = TaskId.generate(),
        userId = UserId.anonymous,
        type = ReminderType.Gentle,
        offsetMinutes = -10,
        fireAt = 0L,
        recurringPattern = recurringPattern,
    )

    @Test
    fun `execute one-shot — shouldDelete=true, uses task title`() {
        val reminder = makeReminder()
        val outcome = ReminderFireLogic.execute(reminder, taskTitle = "Fix bug #42")

        assertEquals("Reminder: Fix bug #42", outcome.title)
        assertEquals("A reminder is due", outcome.body)
        assertTrue(outcome.shouldDelete)
    }

    @Test
    fun `execute one-shot with null taskTitle — falls back to generic title`() {
        val reminder = makeReminder()
        val outcome = ReminderFireLogic.execute(reminder, taskTitle = null)

        assertEquals("Task Reminder", outcome.title)
        assertTrue(outcome.shouldDelete)
    }

    @Test
    fun `execute recurring — shouldDelete=false regardless of taskTitle`() {
        val reminder = makeReminder(recurringPattern = "0 9 * * *")
        val outcome = ReminderFireLogic.execute(reminder, taskTitle = "Daily standup")

        assertEquals("Reminder: Daily standup", outcome.title)
        assertFalse(outcome.shouldDelete)
    }

    @Test
    fun `execute recurring with null taskTitle — shouldDelete=false`() {
        val reminder = makeReminder(recurringPattern = "0 9 * * *")
        val outcome = ReminderFireLogic.execute(reminder, taskTitle = null)

        assertEquals("Task Reminder", outcome.title)
        assertFalse(outcome.shouldDelete)
    }
}
