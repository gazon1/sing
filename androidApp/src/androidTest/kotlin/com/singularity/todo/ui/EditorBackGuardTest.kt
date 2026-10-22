package com.singularity.todo.ui

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.singularity.todo.MainActivity
import com.singularity.todo.testing.InstrumentedTestBase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the system back press does in the task editor.
 *
 * Measured on a real phone (2026-10-28, realme RMX3938, SDK 35), before the
 * handler existed: the editor received the back press with the keyboard open,
 * closed, and the title that had been typed into it was lost. The keyboard hid
 * as a side effect of the screen going away — the gesture was read as
 * "leave", not as "close the keyboard".
 *
 * The contract these tests hold is the one that holds on **every** device:
 * **a back press never throws a draft away without asking.**
 *
 * Whether the keyboard is on the screen decides what the first press means —
 * with it, the press closes the keyboard; without it, the press reaches the
 * screen and asks. A device with a soft keyboard (a phone) and a device
 * without one (an emulator with a hardware keyboard) therefore differ in the
 * *first* press and agree in the outcome. The test asks for the outcome and
 * does not pretend that both devices take the same number of presses.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class EditorBackGuardTest : InstrumentedTestBase() {

    @Before
    fun setup() {
        device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
        device.executeShellCommand("input keyevent 82") // unlock
    }

    @Test
    fun back_press_never_throws_the_draft_away_without_asking() {
        val scenario = launchEditor()

        device.typeInto("task_editor_title_input", draftTitle())

        // The first press either closes the keyboard (a device with a soft
        // keyboard) or reaches the screen (a device without one). The second
        // one always reaches the screen.
        device.tapBack()
        val asked = device.awaitTagOrNull("dialog_confirm", 1_000)
        if (asked == null) {
            device.tapBack()
        }

        val confirm = device.awaitTag("dialog_confirm", "the discard confirmation", TAB_TIMEOUT_MS)
        assertNotNull("the discard confirmation must be there", confirm)

        // "Keep editing" puts the draft back, and the draft is still the draft.
        device.tapTag("dialog_dismiss")
        val field = device.awaitTag("task_editor_title_input", "the task editor's title input", TAB_TIMEOUT_MS)
        assertTrue(
            "the draft must survive the question, got: ${field.text}",
            field.text.contains("Draft"),
        )

        scenario.close()
    }

    @Test
    fun the_editor_is_still_there_after_a_back_press() {
        val scenario = launchEditor()

        device.typeInto("task_editor_title_input", draftTitle())

        // One press, whatever the keyboard did: the editor is either still open
        // or asking. What it must never be is gone with the draft lost.
        device.tapBack()

        val dialog = device.awaitTagOrNull("dialog_confirm", 1_000)
        val field = device.awaitTagOrNull("task_editor_title_input", 1_000)
        assertTrue(
            "a back press must leave the editor or the question, never neither: " +
                "the editor is gone and nothing is asking — the draft was lost",
            dialog != null || field != null,
        )

        scenario.close()
    }

    /**
     * Opens the task editor on a freshly launched app.
     *
     * @return the scenario, so the test can close the app it opened.
     */
    private fun launchEditor(): ActivityScenario<MainActivity> {
        val intent =
            Intent(
                ApplicationProvider.getApplicationContext(),
                MainActivity::class.java,
            )
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        device.awaitShell()
        device.tapTag("tasks_fab")
        device.awaitTag("task_editor_title_input", "the task editor's title input", TAB_TIMEOUT_MS)
        return scenario
    }

    /**
     * A title that is different every time.
     *
     * The task editor restores its draft, so a test that types the same title
     * as the test before it types *nothing* — the field already holds that
     * text, the upstream never hears a change, and the draft stays clean. A
     * clean draft is left without a question, and a test that is about the
     * question would then be measuring the wrong thing.
     */
    private fun draftTitle(): String = "Draft ${System.currentTimeMillis()}"
}
