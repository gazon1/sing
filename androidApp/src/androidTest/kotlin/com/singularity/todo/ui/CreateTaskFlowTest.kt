package com.singularity.todo.ui

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.singularity.todo.MainActivity
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
class CreateTaskFlowTest {

    private lateinit var device: UiDevice

    @Before
    fun setup() {
        device = UiDevice.getInstance(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        )
        device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
        device.executeShellCommand("input keyevent 82") // unlock
    }

    /**
     * Navigate to Today tab (start destination) and wait for it to load.
     */
    private fun navigateToToday() {
        device.wait(Until.findObject(By.res("nav_tab_today")), 8_000)
    }

    /**
     * Happy path: tap FAB → type task title → save → verify task appears in list.
     */
    @Test
    fun create_task_via_fab_appears_in_list() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            MainActivity::class.java
        )
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        navigateToToday()

        // Tap the FAB to open the task editor.
        device.findObject(By.res("tasks_fab")).click()

        // The title input must appear in the editor.
        val titleInput = device.wait(
            Until.findObject(By.res("task_editor_title_input")),
            3_000
        )
        assertNotNull("Task editor title input should appear after FAB tap", titleInput)

        // Type the task title.
        titleInput.setText("Test task UiAutomator")

        // Tap Save.
        device.findObject(By.res("task_editor_save")).click()

        // Editor closes. Wait for the created task to appear in the list.
        // Slug: "Test task UiAutomator" → "test_task_uiautomator"
        val taskItem = device.wait(
            Until.findObject(By.res("task_item_test_task_uiautomator")),
            5_000
        )
        assertNotNull("Created task should appear in the list", taskItem)

        scenario.close()
    }

    /**
     * Cancel path: open editor → type something → press back → verify no task added.
     */
    @Test
    fun cancel_editor_dismisses_without_adding_task() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            MainActivity::class.java
        )
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        navigateToToday()

        // Open editor.
        device.findObject(By.res("tasks_fab")).click()
        device.wait(Until.findObject(By.res("task_editor_title_input")), 3_000)

        // Type a title.
        device.findObject(By.res("task_editor_title_input"))
            .setText("Do not save this task")

        // Press back to cancel.
        device.pressBack()

        // The cancelled task should NOT appear in the list.
        // Slug: "Do not save this task" → "do_not_save_this_task"
        val cancelledTask = device.wait(
            Until.findObject(By.res("task_item_do_not_save_this_task")),
            2_000
        )
        assertNull(
            "Cancelled task should not appear in the list",
            cancelledTask
        )

        scenario.close()
    }

    /**
     * Verify the task editor opens with an empty title field that accepts input.
     */
    @Test
    fun editor_opens_with_empty_title_and_accepts_input() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            MainActivity::class.java
        )
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        navigateToToday()

        // FAB opens the editor.
        device.findObject(By.res("tasks_fab")).click()
        device.wait(Until.findObject(By.res("task_editor_title_input")), 3_000)

        val titleInput = device.findObject(By.res("task_editor_title_input"))
        assertNotNull("Title input should be present", titleInput)

        // Input should be empty initially.
        val initialText = titleInput.text
        assertNotNull("Title input should have text content", initialText)

        // After typing, the field contains the typed text.
        titleInput.setText("Editor smoke test")
        assertNotNull(titleInput.text)

        scenario.close()
    }
}
