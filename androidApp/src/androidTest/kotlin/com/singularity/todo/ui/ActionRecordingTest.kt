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
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The step log records what the test *did*, not only where it waited.
 *
 * ## Why this needs a test of its own
 *
 * The recorder was silent about actions for the whole life of the instrumented
 * suite, and nothing failed when it stayed that way: the log was written, the
 * file was published, and the file said only "waited for X". A recorder that
 * drops half of what happened still produces a file, so the only way to notice
 * is to assert on what it says.
 *
 * The assertions are on the log, not on the app: the app's behaviour is the
 * business of [CreateTaskFlowTest]. What is asserted here is that the
 * interaction the test performed reached the log — with the element, with the
 * action, and with the outcome.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ActionRecordingTest : InstrumentedTestBase() {

    /**
     * A tap is in the log, with the element it touched.
     *
     * The element is the part that makes the line useful: "the tap failed" is
     * a symptom, "the tap on tasks_fab failed" is a place to look.
     */
    @Test
    fun a_tap_is_recorded_with_the_element_it_touched() {
        withAppOpen {
            device.tapTag("tasks_fab")

            val line = stepLine("tap(tasks_fab)")
            assertNotNull("the tap is not in the step log: [$line]", line)
            assertTrue(
                "the tap is not in the step log as a success: [$line]",
                line.orEmpty().contains("OK"),
            )
        }
    }

    /**
     * Typed text is in the log, with the field it went into.
     *
     * The text matters as much as the field: a test that typed the wrong string
     * fails in a way that no screenshot explains.
     */
    @Test
    fun typed_text_is_recorded_with_the_field_it_went_into() {
        withAppOpen {
            device.tapTag("tasks_fab")
            device.typeInto("task_editor_title_input", "Recorded text")

            val line = stepLine("typeText(task_editor_title_input")
            assertNotNull("the typing is not in the step log: [$line]", line)
            assertTrue(
                "the typed text is not in the step log: [$line]",
                line.orEmpty().contains("Recorded text"),
            )
        }
    }

    /**
     * A wait that timed out is recorded as a failure, not as a success.
     *
     * A wait that returns nothing did not do what it was asked; a log that
     * says "OK" for it sends the reader to the wrong place.
     */
    @Test
    fun a_wait_that_found_nothing_is_recorded_as_a_failure() {
        val found = device.awaitTagOrNull("no_such_tag_anywhere", 1_000)

        assertTrue("the tag must not have been found", found == null)
        val line = stepLine("awaitTag(no_such_tag_anywhere)")
        assertNotNull("the failed wait is not in the step log: [$line]", line)
        assertTrue(
            "the failed wait is not in the step log as a failure: [$line]",
            line.orEmpty().contains("FAIL"),
        )
    }

    /**
     * The log line that speaks of [fragment], if there is one.
     *
     * The whole line and not just the fragment, because the outcome of the step
     * lives on the same line and the outcome is what the assertion is about.
     */
    private fun stepLine(fragment: String): String? =
        recordedSteps().lineSequence().firstOrNull { it.contains(fragment) }

    /**
     * Runs [block] with the app open on the shell.
     *
     * The activity is closed in a `finally`, because a test that leaves the
     * app on screen changes what the next test sees.
     */
    private fun withAppOpen(block: () -> Unit) {
        val intent =
            Intent(
                ApplicationProvider.getApplicationContext(),
                MainActivity::class.java,
            )
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        try {
            device.awaitShell()
            block()
        } finally {
            scenario.close()
        }
    }
}
