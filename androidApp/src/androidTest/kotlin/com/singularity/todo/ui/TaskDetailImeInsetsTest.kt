package com.singularity.todo.ui

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
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class TaskDetailImeInsetsTest : InstrumentedTestBase() {
    @Test
    fun focused_task_detail_fields_stay_above_ime() {
        val intent =
            android.content.Intent(
                ApplicationProvider.getApplicationContext(),
                MainActivity::class.java,
            )
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        try {
            bringAppToForeground()
            device.awaitShell()

            val taskTitle = "IME ${UUID.randomUUID().toString().take(8)}"
            openCreatedTaskDetail(taskTitle)

            val statusBarBottom = statusBarBottomInset()

            focusAndAssertAboveIme(
                fieldTag = TestTags.TASK_EDITOR_TITLE_INPUT,
                scrollAnchorTag = null,
                typeText = "T",
            )
            assertContentBelowStatusBar(statusBarBottom)

            focusAndAssertAboveIme(
                fieldTag = TestTags.TASK_EDITOR_DESCRIPTION_INPUT,
                scrollAnchorTag = null,
                typeText = "Desc",
            )
            assertContentBelowStatusBar(statusBarBottom)

            focusAndAssertAboveIme(
                fieldTag = TestTags.CHECKLIST_INLINE_ADD_INPUT,
                scrollAnchorTag = TestTags.TASK_CHECKLIST_SECTION,
                typeText = "Milk",
            )
            assertContentBelowStatusBar(statusBarBottom)

            focusAndAssertAboveIme(
                fieldTag = TestTags.SUBTASK_ADD_INPUT,
                scrollAnchorTag = TestTags.SUBTASKS_SECTION,
                typeText = "Child",
            )
            assertContentBelowStatusBar(statusBarBottom)
        } finally {
            scenario.close()
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

        val save =
            device.awaitTag(
                TestTags.TASK_EDITOR_SAVE,
                "task editor save button",
                TAB_TIMEOUT_MS,
            )
        save.click()

        val rowTag = TestTags.taskItem(taskTitle)
        if (!findAndOpenTaskRow(rowTag)) {
            throw AssertionError(
                "Created task row $rowTag was not found on Today/Inbox after save. ${device.diagnostics()}",
            )
        }

        device.awaitTag(
            TestTags.TASK_EDITOR_TITLE_INPUT,
            "the task detail editor",
            TAB_TIMEOUT_MS,
        )
    }

    private fun findAndOpenTaskRow(rowTag: String): Boolean {
        val tabs = listOf(TODAY_TAB, "nav_tab_inbox")
        for (tab in tabs) {
            runCatching { device.tapTag(tab) }
            device.waitForIdle()
            val row = findVisibleByScroll(rowTag, SCROLL_TIMEOUT_MS / 2)
            if (row != null) {
                row.click()
                return true
            }
        }
        val row = device.findObject(By.res(rowTag))
        if (row != null && !row.visibleBounds.isEmpty) {
            row.click()
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

    private fun bringAppToForeground() {
        device.wakeUp()
        runCatching { device.executeShellCommand("wm dismiss-keyguard") }
        repeat(2) { runCatching { device.pressBack() } }
        device.executeShellCommand("am start -n com.singularity.todo/.MainActivity")
        device.waitForIdle()
        SystemClock.sleep(300)
    }

    private fun focusAndAssertAboveIme(
        fieldTag: String,
        scrollAnchorTag: String?,
        typeText: String,
    ) {
        revealField(fieldTag, scrollAnchorTag)
        clickFieldAndWaitForIme(fieldTag)
        if (typeText.isNotEmpty()) {
            typeWithIme(typeText)
        }

        assertFieldFullyAboveIme(fieldTag)

        // With IME open, back closes keyboard and keeps Task Detail open.
        device.pressBack()
        waitForImeHidden()
        assertTaskDetailOpen()
    }

    private fun clickFieldAndWaitForIme(fieldTag: String) {
        repeat(2) { attempt ->
            val field = scrollToTag(fieldTag)
            field.click()
            if (awaitImeShownOrNull() != null) return
            if (attempt == 0) {
                // Retry once: some fields need an explicit second tap to focus/show IME.
                SystemClock.sleep(250)
            }
        }
        throw AssertionError("IME did not become visible with measurable bounds for $fieldTag. ${device.diagnostics()}")
    }

    private fun assertTaskDetailOpen() {
        // Title can be scrolled offscreen after focusing lower fields; top bar remains.
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
                // Sections can render collapsed content; tapping the section header may reveal inputs.
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

    private fun assertFieldFullyAboveIme(fieldTag: String) {
        val deadline = SystemClock.uptimeMillis() + BOUNDS_TIMEOUT_MS
        var bounds = Rect()
        var imeTop = -1
        while (SystemClock.uptimeMillis() < deadline) {
            bounds = device.awaitTag(fieldTag, "field $fieldTag bounds", TAB_TIMEOUT_MS).visibleBounds
            imeTop = imeTopFromWindowDump() ?: imeTop
            if (imeTop > 0 && !bounds.isEmpty && bounds.bottom <= imeTop - IME_GAP_PX) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError(
            "Expected $fieldTag to be fully above IME: fieldBottom=${bounds.bottom}, imeTop=$imeTop. " +
                "inputShown=${device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")} " +
                device.diagnostics(),
        )
    }

    private fun assertContentBelowStatusBar(statusBarBottom: Int) {
        val title = scrollToTag(TestTags.TASK_EDITOR_TITLE_INPUT)
        val top = title.visibleBounds.top
        assertTrue(
            "Task detail content overlaps status bar: titleTop=$top statusBarBottom=$statusBarBottom. " +
                device.diagnostics(),
            top >= statusBarBottom,
        )
    }

    private fun statusBarBottomInset(): Int {
        val dump = device.executeShellCommand("dumpsys window displays")
        Regex("""mStableFullscreen=Rect\(\d+, (\d+) - \d+, \d+\)""")
            .find(dump)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }
        Regex("""contentInsets=\[\d+,(\d+)\]""")
            .find(dump)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }
        return 0
    }

    private fun awaitImeShownOrNull(): Int? {
        val deadline = SystemClock.uptimeMillis() + IME_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val input = device.executeShellCommand("dumpsys input_method")
            val imeTop = imeTopFromWindowDump()
            if (input.contains("mInputShown=true") && imeTop != null) return imeTop
            SystemClock.sleep(POLL_MS)
        }
        return null
    }

    private fun waitForImeShown() {
        awaitImeShownOrNull()
            ?: throw AssertionError("IME did not become visible with measurable bounds. ${device.diagnostics()}")
    }

    private fun waitForImeHidden() {
        val deadline = SystemClock.uptimeMillis() + IME_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val input = device.executeShellCommand("dumpsys input_method")
            if (!input.contains("mInputShown=true")) return
            SystemClock.sleep(POLL_MS)
        }
    }

    private fun hideImeIfShown() {
        val input = device.executeShellCommand("dumpsys input_method")
        if (input.contains("mInputShown=true")) {
            device.pressBack()
            waitForImeHidden()
        }
    }

    private fun imeTopFromWindowDump(): Int? {
        val dump = device.executeShellCommand("dumpsys window windows")
        val displayHeight = device.displayHeight
        val displayWidth = device.displayWidth
        val lines = dump.lineSequence().toList()
        val imeIndex = lines.indexOfFirst { it.contains("InputMethod") && it.contains("Window{") }
        val candidates = mutableListOf<Int>()

        if (imeIndex >= 0) {
            val block = lines.drop(imeIndex).take(260).joinToString("\n")
            val rects = Regex("""\[(\-?\d+),(\-?\d+)\]\[(\-?\d+),(\-?\d+)\]""").findAll(block)
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
                if (plausibleKeyboard) {
                    candidates += top
                }
            }
        }

        if (candidates.isNotEmpty()) return candidates.min()

        val windowDump = device.executeShellCommand("dumpsys window")
        Regex("""mImeHeight=(\d+)""").find(windowDump)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { h ->
            if (h >= 120) return (displayHeight - h).coerceAtLeast(0)
        }
        Regex("""imeHeight=(\d+)""").find(windowDump)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { h ->
            if (h >= 120) return (displayHeight - h).coerceAtLeast(0)
        }

        return null
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
        val x = w / 2
        val startY = (h * 0.72).toInt()
        val endY = (h * 0.28).toInt()
        device.swipe(x, startY, x, endY, 20)
        device.waitForIdle()
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
