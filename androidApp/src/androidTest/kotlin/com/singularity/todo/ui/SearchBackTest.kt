package com.singularity.todo.ui

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.singularity.todo.MainActivity
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.testing.InstrumentedTestBase
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the system back press does on the search screen.
 *
 * The screen asks the field for the focus when it appears, so the keyboard is up
 * before the user does anything. A back press in that state must close the
 * keyboard and leave the screen — and the query — where they are; only the next
 * press may leave the screen.
 *
 * The desktop suite cannot hold this contract: `PlatformBackHandler` is a no-op
 * there, by design. This class is the same question asked on a device, where the
 * system back exists.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SearchBackTest : InstrumentedTestBase() {
    @Test
    fun back_with_the_keyboard_up_closes_the_keyboard_before_it_leaves_the_screen() {
        val scenario = launchSearch()
        try {
            requireImeForSearchField()
            typeIntoSearch(QUERY)

            device.tapBack()
            assertTrue("the keyboard must close on the first back press", waitForImeHidden())

            val field = device.awaitTagOrNull(TestTags.SEARCH_INPUT, TAB_TIMEOUT_MS)
            assertTrue("the first back press must not leave the search screen", field != null)
            assertTrue(
                "the query must survive the keyboard dismissal, got: ${field?.text}",
                field?.text?.contains(QUERY) == true,
            )

            device.tapBack()
            assertTrue(
                "the second back press must leave the search screen",
                waitForSearchGone(),
            )
        } finally {
            scenario.close()
        }
    }

    @Test
    fun back_with_the_filter_sheet_open_dismisses_the_sheet_and_keeps_the_query() {
        val scenario = launchSearch()
        try {
            requireImeForSearchField()
            typeIntoSearch(QUERY)

            // The first press is the keyboard's, as pinned above. The sheet is
            // opened with the keyboard out of the way, so the press below can be
            // about the sheet alone.
            device.tapBack()
            assertTrue("the keyboard must close before the sheet is opened", waitForImeHidden())

            awaitFilterButton().tap("search filter")
            // The sheet opens at half height: its actions are below the fold, so
            // the visible marker is a section the sheet draws at the top.
            assertTrue(
                "the filter sheet must open before a back press can dismiss it",
                waitForText(SHEET_MARKER),
            )

            device.tapBack()
            assertTrue(
                "the filter sheet must be the first thing a back press dismisses",
                waitForTextGone(SHEET_MARKER),
            )

            val field = device.awaitTagOrNull(TestTags.SEARCH_INPUT, TAB_TIMEOUT_MS)
            assertTrue("dismissing the sheet must not leave the search screen", field != null)
            assertTrue(
                "dismissing the sheet must not change the query, got: ${field?.text}",
                field?.text?.contains(QUERY) == true,
            )
        } finally {
            scenario.close()
        }
    }

    /**
     * Opens the app, then Quick search from the shell menu.
     *
     * The search field comes up focused, and the keyboard follows it. The
     * device's keyguard is dismissed first: a locked screen answers the first
     * press with itself, which would make the test measure the lock screen.
     */
    private fun launchSearch(): ActivityScenario<MainActivity> {
        val intent =
            Intent(
                ApplicationProvider.getApplicationContext(),
                MainActivity::class.java,
            )
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        device.wakeUp()
        runCatching { device.executeShellCommand("wm dismiss-keyguard") }
        device.awaitShell()
        device.tapTag(TestTags.NAV_MENU_BUTTON)
        device.tapTag(TestTags.MENU_QUICK_SEARCH)
        device.awaitTag(TestTags.SEARCH_INPUT, "the search field", TAB_TIMEOUT_MS)
        return scenario
    }

    /**
     * Taps the field until the keyboard is measurably on screen.
     *
     * The field asks for the focus itself, so the keyboard is often already up.
     * A click is harmless in that case and is what makes the keyboard appear on a
     * device where the focus request alone did not raise it.
     */
    private fun requireImeForSearchField() {
        repeat(2) { attempt ->
            device.findObject(By.res(TestTags.SEARCH_INPUT))?.click()
            if (waitForImeShown()) return
            if (attempt == 0) SystemClock.sleep(250)
        }
        throw AssertionError(
            "IME did not become visible with measurable bounds for ${TestTags.SEARCH_INPUT}. " +
                device.diagnostics(),
        )
    }

    private fun typeIntoSearch(query: String) {
        device.executeShellCommand("input text $query")
        device.waitForIdle()
        SystemClock.sleep(350)
    }

    private fun awaitFilterButton(): UiObject2 =
        device.wait(Until.findObject(By.desc("Filter")), TAB_TIMEOUT_MS)
            ?: throw AssertionError("the search screen's filter button did not appear. ${device.diagnostics()}")

    private fun waitForTagGone(tag: String): Boolean {
        val deadline = SystemClock.uptimeMillis() + TAB_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (device.findObject(By.res(tag)) == null) return true
            SystemClock.sleep(POLL_MS)
        }
        return false
    }

    private fun waitForSearchGone(): Boolean = waitForTagGone(TestTags.SEARCH_INPUT)

    private fun waitForText(text: String): Boolean {
        val deadline = SystemClock.uptimeMillis() + TAB_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (device.findObject(By.text(text)) != null) return true
            SystemClock.sleep(POLL_MS)
        }
        return false
    }

    private fun waitForTextGone(text: String): Boolean {
        val deadline = SystemClock.uptimeMillis() + TAB_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (device.findObject(By.text(text)) == null) return true
            SystemClock.sleep(POLL_MS)
        }
        return false
    }

    private fun waitForImeShown(): Boolean = waitForIme(shown = true)

    private fun waitForImeHidden(): Boolean = waitForIme(shown = false)

    /**
     * Waits for `dumpsys input_method` to report the keyboard [shown] or hidden.
     *
     * The same probe `TaskDetailImeInsetsTest` uses: the keyboard's own window is
     * not in the app's semantics tree, so the device is the only thing that can
     * say whether it is up.
     */
    private fun waitForIme(shown: Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + IME_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val input = device.executeShellCommand("dumpsys input_method")
            val window = device.executeShellCommand("dumpsys window")
            val shownNow = input.contains("mInputShown=true")
            val height =
                Regex("""\b(?:mImeHeight|imeHeight)=(\d+)""")
                    .find(window)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull() ?: 0
            if (shownNow == shown && (!shown || height > 0)) return true
            SystemClock.sleep(POLL_MS)
        }
        return false
    }

    companion object {
        private const val QUERY = "milk"

        /** A section the filter sheet draws above its fold. */
        private const val SHEET_MARKER = "Status"
        private const val IME_TIMEOUT_MS = 12_000L
        private const val POLL_MS = 250L
    }
}
