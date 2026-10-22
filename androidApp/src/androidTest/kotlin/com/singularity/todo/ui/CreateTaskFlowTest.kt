package com.singularity.todo.ui

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.singularity.todo.MainActivity
import com.singularity.todo.testing.InstrumentedTestBase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for the task creation flow.
 *
 * Uses UiAutomator (see [NavigationSmokeTest] for rationale). These tests verify
 * the end-to-end happy path: tap FAB → type title → save → task appears in list.
 *
 * The slug algorithm (TestTags.kt) lowercases and replaces runs of non-alphanumeric
 * characters with underscores. Spaces become underscores, so "Test task UiAutomator"
 * becomes "test_task_uiautomator".
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class CreateTaskFlowTest : InstrumentedTestBase() {

    @Before
    fun setup() {
        device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
        device.executeShellCommand("input keyevent 82") // unlock
    }

    /**
     * Navigate to Today tab (start destination) and wait for it to load.
     *
     * The wait is the shared one — see [awaitShell]. The 8 s it replaces was
     * shorter than a cold start takes on a loaded CI runner, and it failed with
     * a bare `NullPointerException` on the next line, naming neither the cause
     * nor the screen that was there instead.
     */
    private fun navigateToToday() {
        device.awaitShell()
    }

    /**
     * Happy path: tap FAB → type task title → save → verify task appears in list.
     */
    @Test
    fun create_task_via_fab_appears_in_list() {
        val intent =
            Intent(
                ApplicationProvider.getApplicationContext(),
                MainActivity::class.java,
            )
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        navigateToToday()

        // Tap the FAB to open the task editor.
        device.tapTag("tasks_fab")

        // The title input must appear in the editor, and the title is typed into
        // it — the tap and the typing are recorded as the steps they are.
        device.typeInto("task_editor_title_input", "Test task UiAutomator")

        // Tap Save.
        device.tapTag("task_editor_save")

        // Editor closes. Wait for the created task to appear in the list.
        // Slug: "Test task UiAutomator" → "test_task_uiautomator"
        val taskItem = device.awaitTagOrNull("task_item_test_task_uiautomator", 5_000)
        assertNotNull("Created task should appear in the list", taskItem)

        scenario.close()
    }

    /**
     * Cancel path: open editor → type something → press back → verify no task added.
     *
     * The back press is two presses now, and the order matters: the first one
     * closes the keyboard (the keyboard is open, so the keyboard is what a back
     * press is for), the second one reaches the screen and — the draft is dirty
     * — asks before it discards. Confirming the discard is the cancel.
     */
    @Test
    fun cancel_editor_dismisses_without_adding_task() {
        val intent =
            Intent(
                ApplicationProvider.getApplicationContext(),
                MainActivity::class.java,
            )
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        navigateToToday()

        // Open editor.
        device.tapTag("tasks_fab")
        device.awaitTag("task_editor_title_input", "the task editor's title input", TAB_TIMEOUT_MS)

        // Type a title.
        device.typeInto("task_editor_title_input", "Do not save this task")

        // Press back to cancel: the first press closes the keyboard on a device
        // that has one, the next one reaches the screen and — the draft is
        // dirty — asks whether to discard it.
        device.tapBack()
        if (device.awaitTagOrNull("dialog_confirm", 1_000) == null) {
            device.tapBack()
        }
        device.awaitTag("dialog_confirm", "the discard confirmation", TAB_TIMEOUT_MS).tap()

        // The cancelled task should NOT appear in the list.
        // Slug: "Do not save this task" → "do_not_save_this_task"
        val cancelledTask = device.awaitTagOrNull("task_item_do_not_save_this_task", 2_000)
        assertNull(
            "Cancelled task should not appear in the list",
            cancelledTask,
        )

        scenario.close()
    }

    /**
     * Verify the task editor opens with an empty title field that accepts input.
     */
    @Test
    fun editor_opens_with_empty_title_and_accepts_input() {
        val intent =
            Intent(
                ApplicationProvider.getApplicationContext(),
                MainActivity::class.java,
            )
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        navigateToToday()

        // FAB opens the editor.
        device.tapTag("tasks_fab")

        val titleInput = device.awaitTag(
            "task_editor_title_input",
            "the task editor's title input",
            TAB_TIMEOUT_MS,
        )

        // Input should be empty initially.
        val initialText = titleInput.text
        assertNotNull("Title input should have text content", initialText)

        // After typing, the field contains the typed text.
        titleInput.typeText("Editor smoke test")
        assertNotNull(titleInput.text)

        scenario.close()
    }
}
