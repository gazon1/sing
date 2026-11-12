package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.assertTextNotExists
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * The reminder row of the task detail.
 *
 * The row has to keep showing the offset when the notifications are off: the
 * reminder is set, it simply cannot fire, and a row that swapped its value for
 * a warning would hide the thing the user came to check.
 */
@Tag("fast")
@OptIn(ExperimentalTestApi::class)
class TaskEditorReminderRowTest {
    @Test
    fun `shows the offset the task carries`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent {
                TaskEditorReminderRow(
                    reminderOffset = ReminderOffset.ONE_HOUR,
                    notificationsEnabled = true,
                    onReminderClick = {},
                    onReminderClear = {},
                )
            }

            assertTextDisplayed("1 hour before")
        }

    @Test
    fun `shows the offset and the notice when the notifications are off`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent {
                TaskEditorReminderRow(
                    reminderOffset = ReminderOffset.ONE_HOUR,
                    notificationsEnabled = false,
                    onReminderClick = {},
                    onReminderClear = {},
                )
            }

            // One line, one fact: the offset *and* the reason it cannot fire.
            assertTextDisplayed("1 hour before · Notifications are off")
        }

    @Test
    fun `says that the notifications are off when there is no reminder`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent {
                TaskEditorReminderRow(
                    reminderOffset = null,
                    notificationsEnabled = false,
                    onReminderClick = {},
                    onReminderClear = {},
                )
            }

            assertTextDisplayed("Notifications are off")
            assertTextNotExists("Reminder")
        }
}
