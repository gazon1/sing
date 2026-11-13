package com.singularity.todo.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import com.singularity.todo.MainActivity
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.testing.InstrumentedTestBase
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Task Detail must keep edited fields above the IME and respect system bars (#2874).
 *
 * Geometry is measured from device-reported window insets and UiAutomator bounds in the
 * same screen coordinate space. Unknown IME/system-bar state is a test failure, not a pass.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class TaskDetailImeInsetsTest : InstrumentedTestBase() {
    @Test
    fun focused_task_detail_fields_stay_above_ime() {
        val intent =
            Intent(
                ApplicationProvider.getApplicationContext(),
                MainActivity::class.java,
            )
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        try {
            device.wakeUp()
            runCatching { device.executeShellCommand("wm dismiss-keyguard") }
            device.awaitShell()
            clearTaskEditorDrafts()

            val taskTitle = "IME ${UUID.randomUUID().toString().take(8)}"
            openCreatedTaskDetail(taskTitle)

            val statusBarBottom = requireStatusBarBottom()

            focusAndAssertAboveIme(TestTags.TASK_EDITOR_TITLE_INPUT, scrollAnchorTag = null)
            assertContentBelowStatusBar(statusBarBottom)

            focusAndAssertAboveIme(TestTags.TASK_EDITOR_DESCRIPTION_INPUT, scrollAnchorTag = null)
            assertContentBelowStatusBar(statusBarBottom)

            focusAndAssertAboveIme(
                TestTags.CHECKLIST_INLINE_ADD_INPUT,
                scrollAnchorTag = TestTags.TASK_CHECKLIST_SECTION,
            )
            assertContentBelowStatusBar(statusBarBottom)

            focusAndAssertAboveIme(
                TestTags.SUBTASK_ADD_INPUT,
                scrollAnchorTag = TestTags.SUBTASKS_SECTION,
            )
            assertContentBelowStatusBar(statusBarBottom)
        } finally {
            scenario.close()
            clearTaskEditorDrafts()
        }
    }

    private fun openCreatedTaskDetail(taskTitle: String) {
        device.tapTag(TODAY_TAB)
        device.tapTag(TestTags.TASKS_FAB)
        val titleInput =
            device.awaitTag(
                TestTags.TASK_EDITOR_TITLE_INPUT,
                "task create title input",
                TAB_TIMEOUT_MS,
            )
        titleInput.click()
        typeWithIme(taskTitle)
        hideImeIfShown()

        device
            .awaitTag(
                TestTags.TASK_EDITOR_SAVE,
                "task editor save button",
                TAB_TIMEOUT_MS,
            ).tap(TestTags.TASK_EDITOR_SAVE)

        val rowTag = TestTags.taskItem(taskTitle)
        val opened = findAndOpenTaskRow(rowTag)
        assertTrue(
            "Created task row $rowTag was not found on Today/Inbox after save. ${device.diagnostics()}",
            opened,
        )

        device.awaitTag(
            TestTags.TASK_EDITOR_TITLE_INPUT,
            "the task detail editor",
            TAB_TIMEOUT_MS,
        )
    }

    private fun findAndOpenTaskRow(rowTag: String): Boolean {
        for (tab in listOf(TODAY_TAB, "nav_tab_inbox")) {
            runCatching { device.tapTag(tab) }
            device.waitForIdle()
            val row = findVisibleByScroll(rowTag, SCROLL_TIMEOUT_MS / 2)
            if (row != null) {
                row.tap(rowTag)
                return true
            }
        }
        val row = device.findObject(By.res(rowTag))
        if (row != null && !row.visibleBounds.isEmpty) {
            row.tap(rowTag)
            return true
        }
        return false
    }

    private fun findVisibleByScroll(
        tag: String,
        timeoutMs: Long,
    ): UiObject2? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            device.findObject(By.res(tag))?.let { obj ->
                if (!obj.visibleBounds.isEmpty) return obj
            }
            swipeUp()
            SystemClock.sleep(POLL_MS)
        }
        return device.findObject(By.res(tag))?.takeIf { !it.visibleBounds.isEmpty }
    }

    private fun focusAndAssertAboveIme(
        fieldTag: String,
        scrollAnchorTag: String?,
    ) {
        revealField(fieldTag, scrollAnchorTag)
        clickFieldAndRequireIme(fieldTag)

        val imeTop = requireImeTop()
        val bounds = requireFieldBounds(fieldTag)
        assertFieldAboveIme(fieldTag, bounds, imeTop)

        // With IME open, back closes keyboard and keeps Task Detail open.
        device.tapBack()
        waitForImeHidden()
        assertTaskDetailOpen()
    }

    private fun clickFieldAndRequireIme(fieldTag: String) {
        repeat(2) { attempt ->
            scrollToTag(fieldTag).tap(fieldTag)
            if (waitForImeShown()) return
            if (attempt == 0) SystemClock.sleep(250)
        }
        throw AssertionError(
            "IME did not become visible with measurable bounds for $fieldTag. ${device.diagnostics()}",
        )
    }

    private fun assertTaskDetailOpen() {
        device.awaitTag(TestTags.TASK_EDITOR_MORE_MENU, "task detail remains open", TAB_TIMEOUT_MS)
    }

    private fun revealField(
        fieldTag: String,
        scrollAnchorTag: String?,
    ) {
        if (scrollAnchorTag != null) {
            scrollToTag(scrollAnchorTag)
            val existing = device.findObject(By.res(fieldTag))
            if (existing == null || existing.visibleBounds.isEmpty) {
                runCatching { device.tapTag(scrollAnchorTag) }
                device.waitForIdle()
                SystemClock.sleep(350)
            }
        }
        scrollToTag(fieldTag)
    }

    private fun typeWithIme(text: String) {
        device.executeShellCommand("input text ${shellText(text)}")
        device.waitForIdle()
        SystemClock.sleep(350)
    }

    private fun requireFieldBounds(fieldTag: String): Rect {
        val deadline = SystemClock.uptimeMillis() + BOUNDS_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val obj = device.findObject(By.res(fieldTag))
            val bounds = obj?.visibleBounds
            if (bounds != null && !bounds.isEmpty) return bounds
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("Field $fieldTag has no visible bounds. ${device.diagnostics()}")
    }

    private fun assertFieldAboveIme(
        fieldTag: String,
        initialBounds: Rect,
        initialImeTop: Int,
    ) {
        val deadline = SystemClock.uptimeMillis() + BOUNDS_TIMEOUT_MS
        var bounds = initialBounds
        var imeTop = initialImeTop
        while (SystemClock.uptimeMillis() < deadline) {
            bounds = requireFieldBounds(fieldTag)
            imeTop = requireImeTop()
            if (bounds.bottom <= imeTop - IME_GAP_PX) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError(
            "Expected $fieldTag above IME: fieldBottom=${bounds.bottom} imeTop=$imeTop " +
                "field=$bounds. ${device.diagnostics()}",
        )
    }

    private fun assertContentBelowStatusBar(statusBarBottom: Int) {
        // Title/top bar are near the top; after focusing lower fields we may be scrolled down.
        val title = scrollToTop(TestTags.TASK_EDITOR_TITLE_INPUT)
        val titleTop = title.visibleBounds.top
        assertTrue(
            "Task detail content overlaps status bar: titleTop=$titleTop statusBarBottom=$statusBarBottom. " +
                device.diagnostics(),
            titleTop >= statusBarBottom,
        )

        val moreMenu = device.findObject(By.res(TestTags.TASK_EDITOR_MORE_MENU))?.visibleBounds
        if (moreMenu != null && !moreMenu.isEmpty) {
            assertTrue(
                "Task detail top bar overlaps status bar: menuTop=${moreMenu.top} statusBarBottom=$statusBarBottom. " +
                    device.diagnostics(),
                moreMenu.top >= statusBarBottom,
            )
        }
    }

    private fun requireStatusBarBottom(): Int {
        val dump = device.executeShellCommand("dumpsys window displays")
        val patterns =
            listOf(
                Regex("""InsetsSource[^\n]*type=statusBars[^\n]*frame=\[0,0\]\[\d+,(\d+)\]"""),
                Regex("""type=statusBars[^\n]*mInsetsHint=Insets\{left=\d+, top=(\d+),"""),
                Regex("""statusBars=Insets\[left=\d+, top=(\d+), right=\d+, bottom=\d+\]"""),
                Regex("""mStableFullscreen=Rect\(\d+, (\d+) - \d+, \d+\)"""),
                Regex("""contentInsets=\[\d+,(\d+)\]"""),
                Regex("""systemWindowInsets=\{l=\d+,t=(\d+),r=\d+,b=\d+\}"""),
            )
        for (regex in patterns) {
            val value =
                regex
                    .findAll(dump)
                    .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                    .firstOrNull { it > 0 }
            if (value != null) return value
        }
        // Some devices report 0 only when truly edge-to-edge/not applicable; treat unknown as failure.
        if (dump.contains("statusBars") || dump.contains("Insets")) {
            throw AssertionError(
                "Could not determine status bar bottom inset from dumpsys window displays. $dump ${device.diagnostics()}",
            )
        }
        throw AssertionError(
            "Could not read status bar insets (dumpsys window displays unavailable/empty). ${device.diagnostics()}",
        )
    }

    private fun waitForImeShown(): Boolean {
        val deadline = SystemClock.uptimeMillis() + IME_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (imeTopOrNull() != null) return true
            SystemClock.sleep(POLL_MS)
        }
        return false
    }

    private fun requireImeTop(): Int =
        imeTopOrNull()
            ?: throw AssertionError("Could not determine IME top while IME is required. ${device.diagnostics()}")

    private fun imeTopOrNull(): Int? {
        val input = device.executeShellCommand("dumpsys input_method")
        if (!input.contains("mInputShown=true")) return null
        val window = device.executeShellCommand("dumpsys window")
        val displayHeight = device.displayHeight
        Regex("""\b(?:mImeHeight|imeHeight)=(\d+)""")
            .find(window)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it >= 120 }
            ?.let { return (displayHeight - it).coerceAtLeast(0) }

        val rectTop = imeTopFromWindowRects()
        if (rectTop != null && rectTop in (displayHeight / 4)..(displayHeight - 100)) return rectTop
        return null
    }

    private fun waitForImeHidden() {
        val deadline = SystemClock.uptimeMillis() + IME_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val input = device.executeShellCommand("dumpsys input_method")
            val window = device.executeShellCommand("dumpsys window")
            val imeHeight =
                Regex("""\b(?:mImeHeight|imeHeight)=(\d+)""")
                    .find(window)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull() ?: 0
            if (!input.contains("mInputShown=true") || imeHeight == 0) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("IME did not hide within ${IME_TIMEOUT_MS}ms. ${device.diagnostics()}")
    }

    private fun hideImeIfShown() {
        val input = device.executeShellCommand("dumpsys input_method")
        val imeHeight =
            Regex("""\b(?:mImeHeight|imeHeight)=(\d+)""")
                .find(device.executeShellCommand("dumpsys window"))
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull() ?: 0
        if (input.contains("mInputShown=true") || imeHeight > 0) {
            device.tapBack()
            waitForImeHidden()
        }
    }

    private fun imeTopFromWindowRects(): Int? {
        val dump = device.executeShellCommand("dumpsys window windows")
        val displayHeight = device.displayHeight
        val displayWidth = device.displayWidth
        val lines = dump.lineSequence().toList()
        val imeIndex = lines.indexOfFirst { it.contains("InputMethod") && it.contains("Window{") }
        if (imeIndex < 0) return null
        val block = lines.drop(imeIndex).take(260).joinToString("\n")
        val rects = Regex("""\[(\-?\d+),(\-?\d+)\]\[(\-?\d+),(\-?\d+)\]""").findAll(block)
        val candidates = mutableListOf<Int>()
        for (match in rects) {
            val left = match.groupValues[1].toInt()
            val top = match.groupValues[2].toInt()
            val right = match.groupValues[3].toInt()
            val bottom = match.groupValues[4].toInt()
            val width = right - left
            val height = bottom - top
            val plausibleKeyboard =
                left <= displayWidth / 4 &&
                    width >= (displayWidth * 0.6).toInt() &&
                    bottom >= displayHeight - 120 &&
                    top >= displayHeight / 4 &&
                    top <= displayHeight - 100 &&
                    height >= 120
            if (plausibleKeyboard) candidates += top
        }
        return candidates.minOrNull()
    }

    private fun scrollToTag(tag: String): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + SCROLL_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            device.findObject(By.res(tag))?.let { obj ->
                if (!obj.visibleBounds.isEmpty) return obj
            }
            swipeUp()
            SystemClock.sleep(POLL_MS)
        }
        return device.awaitTag(tag, "scroll target $tag", TAB_TIMEOUT_MS)
    }

    private fun swipeUp() {
        val w = device.displayWidth
        val h = device.displayHeight
        // Finger moves up => content scrolls down (reveals lower content).
        device.swipe(w / 2, (h * 0.72).toInt(), w / 2, (h * 0.28).toInt(), 20)
        device.waitForIdle()
    }

    private fun swipeDown() {
        val w = device.displayWidth
        val h = device.displayHeight
        // Finger moves down => content scrolls up (reveals higher content).
        device.swipe(w / 2, (h * 0.28).toInt(), w / 2, (h * 0.72).toInt(), 20)
        device.waitForIdle()
    }

    private fun scrollToTop(tag: String): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + SCROLL_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            device.findObject(By.res(tag))?.let { obj ->
                if (!obj.visibleBounds.isEmpty) return obj
            }
            swipeDown()
            SystemClock.sleep(POLL_MS)
        }
        return device.awaitTag(tag, "scroll-to-top target $tag", TAB_TIMEOUT_MS)
    }

    private fun shellText(text: String): String =
        text
            .replace("\\", "\\\\")
            .replace(" ", "%s")
            .replace("\"", "\\\"")
            .replace("'", "\\'")

    companion object {
        private const val IME_TIMEOUT_MS = 12_000L
        private const val BOUNDS_TIMEOUT_MS = 12_000L
        private const val SCROLL_TIMEOUT_MS = 12_000L
        private const val POLL_MS = 250L
        private const val IME_GAP_PX = 2
    }
}
