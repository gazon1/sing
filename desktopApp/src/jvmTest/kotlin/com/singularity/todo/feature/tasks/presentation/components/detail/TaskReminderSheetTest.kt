@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.tasks.presentation.components.detail

import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.core.reminders.ReminderOffsetState
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagSelected
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reminder sheet of the task detail.
 *
 * Four things are pinned here. The sheet has to open on the offset the task
 * already has — a picker that opens on nothing makes the user guess what is
 * set — a refusal of the notification permission has to say what is wrong
 * and offer a way out, the sheet has to apply its change only on its
 * confirm button, and a task without a due date has to say that the date
 * comes first.
 */
@Tag("fast")
class TaskReminderSheetTest {
    @Test
    fun `the sheet opens on the offset the task already has`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent {
                TaskReminderSheet(
                    currentState = ReminderOffsetState.Known(ReminderOffset.ONE_HOUR),
                    hasDueDate = true,
                    notificationsEnabled = true,
                    onSelect = {},
                    onClear = {},
                    onRequestPermission = null,
                    onOpenSettings = null,
                    onDismiss = {},
                )
            }

            assertTagSelected(TestTags.TASK_REMINDER_OPTION_1_HOUR)
        }

    @Test
    fun `choosing an offset writes it on save`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            var chosen: ReminderOffset? = null
            setContent {
                TaskReminderSheet(
                    currentState = ReminderOffsetState.None,
                    hasDueDate = true,
                    notificationsEnabled = true,
                    onSelect = { chosen = it },
                    onClear = {},
                    onRequestPermission = null,
                    onOpenSettings = null,
                    onDismiss = {},
                )
            }

            clickTag(TestTags.TASK_REMINDER_OPTION_15_MIN)
            clickTag(TestTags.TASK_REMINDER_CONFIRM)

            assertEquals(ReminderOffset.FIFTEEN_MIN, chosen)
        }

    @Test
    fun `cancelling writes nothing at all`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            var written = false
            setContent {
                TaskReminderSheet(
                    currentState = ReminderOffsetState.Known(ReminderOffset.ONE_HOUR),
                    hasDueDate = true,
                    notificationsEnabled = true,
                    onSelect = { written = true },
                    onClear = {},
                    onRequestPermission = null,
                    onOpenSettings = null,
                    onDismiss = {},
                )
            }

            clickTag(TestTags.TASK_REMINDER_OPTION_1_DAY)
            clickTag(TestTags.TASK_REMINDER_CANCEL)

            assertFalse(written, "a cancelled sheet writes nothing")
        }

    /**
     * «Очистить» marks the removal; «Сохранить» applies it.
     *
     * The sheet used to delete the reminder the moment it was cleared, so a user
     * who changed their mind could not call it back — and every other sheet in
     * the app applies its change on the confirm button.
     */
    @Test
    fun `clearing and saving takes the reminder away`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            var cleared = false
            setContent {
                TaskReminderSheet(
                    currentState = ReminderOffsetState.Known(ReminderOffset.ONE_HOUR),
                    hasDueDate = true,
                    notificationsEnabled = true,
                    onSelect = {},
                    onClear = { cleared = true },
                    onRequestPermission = null,
                    onOpenSettings = null,
                    onDismiss = {},
                )
            }

            clickTag(TestTags.TASK_REMINDER_CLEAR)
            clickTag(TestTags.TASK_REMINDER_CONFIRM)

            assertTrue(cleared, "the reminder is gone once the sheet is saved")
        }

    @Test
    fun `clearing and cancelling keeps the reminder`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            var cleared = false
            setContent {
                TaskReminderSheet(
                    currentState = ReminderOffsetState.Known(ReminderOffset.ONE_HOUR),
                    hasDueDate = true,
                    notificationsEnabled = true,
                    onSelect = {},
                    onClear = { cleared = true },
                    onRequestPermission = null,
                    onOpenSettings = null,
                    onDismiss = {},
                )
            }

            clickTag(TestTags.TASK_REMINDER_CLEAR)
            clickTag(TestTags.TASK_REMINDER_CANCEL)

            assertFalse(cleared, "a cancelled sheet writes nothing")
        }

    @Test
    fun `the notice about the notifications says what is wrong and offers a way out`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            var settingsOpened = false
            setContent {
                TaskReminderSheet(
                    currentState = ReminderOffsetState.Known(ReminderOffset.ONE_HOUR),
                    hasDueDate = true,
                    notificationsEnabled = false,
                    onSelect = {},
                    onClear = {},
                    onRequestPermission = null,
                    onOpenSettings = { settingsOpened = true },
                    onDismiss = {},
                )
            }

            assertTextDisplayed("Notifications are off")
            assertTagDisplayed(TestTags.TASK_REMINDER_NOTICE)
            assertTagDisplayed(TestTags.TASK_REMINDER_SETTINGS_ACTION)

            clickTag(TestTags.TASK_REMINDER_SETTINGS_ACTION)

            assertTrue(settingsOpened, "the settings action opens the settings")
        }

    /**
     * A task without a due date has no moment to count back from, so the sheet
     * says the date comes first — the slot refuses the write.
     */
    @Test
    fun `a task without a due date is told that the date comes first`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent {
                TaskReminderSheet(
                    currentState = ReminderOffsetState.None,
                    hasDueDate = false,
                    notificationsEnabled = true,
                    onSelect = {},
                    onClear = {},
                    onRequestPermission = null,
                    onOpenSettings = null,
                    onDismiss = {},
                )
            }

            assertTagDisplayed(TestTags.TASK_REMINDER_NEEDS_DUE_DATE)
            assertTextDisplayed("Set a due date to use a reminder")
        }

    /**
     * A reminder this build does not know the offset of is still a reminder:
     * the sheet says so, and saving it without touching anything must not
     * rewrite it into the nearest offset the app happens to offer.
     */
    @Test
    fun `an unknown offset is not rewritten when the sheet is saved`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            var chosen: ReminderOffset? = null
            var cleared = false
            setContent {
                TaskReminderSheet(
                    currentState = ReminderOffsetState.Unknown(37),
                    hasDueDate = true,
                    notificationsEnabled = true,
                    onSelect = { chosen = it },
                    onClear = { cleared = true },
                    onRequestPermission = null,
                    onOpenSettings = null,
                    onDismiss = {},
                )
            }

            assertTagDisplayed(TestTags.TASK_REMINDER_UNKNOWN_OFFSET)

            clickTag(TestTags.TASK_REMINDER_CONFIRM)

            assertEquals(null, chosen, "an unknown offset must not be rewritten")
            assertFalse(cleared, "saving is not a removal")
        }

    /**
     * The other half: a reminder the user cannot read the offset of is still a
     * reminder they own, so they must be able to take it away.
     */
    @Test
    fun `an unknown offset can be removed`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            var cleared = false
            setContent {
                TaskReminderSheet(
                    currentState = ReminderOffsetState.Unknown(37),
                    hasDueDate = true,
                    notificationsEnabled = true,
                    onSelect = {},
                    onClear = { cleared = true },
                    onRequestPermission = null,
                    onOpenSettings = null,
                    onDismiss = {},
                )
            }

            assertTagDisplayed(TestTags.TASK_REMINDER_CLEAR)

            clickTag(TestTags.TASK_REMINDER_CLEAR)
            clickTag(TestTags.TASK_REMINDER_CONFIRM)

            assertTrue(cleared, "the unknown reminder can still be removed")
        }
}
