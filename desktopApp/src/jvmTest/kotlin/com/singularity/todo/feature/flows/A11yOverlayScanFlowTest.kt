package com.singularity.todo.feature.flows

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.a11yScan
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.clickContentDescription
import com.singularity.todo.test.helpers.clickContentDescriptionScrolled
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.clickTagScrolled
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The accessibility scan has to be able to run **while an overlay is open**.
 *
 * ## The gap this closes
 *
 * `runDesktopAppTest(checkA11y = true)` runs the scan after the test body
 * succeeds — by which time a sheet or a dialog has usually been dismissed. The
 * overlay's own semantics root is gone, and the scan never saw the control
 * that was unlabelled: an unlabelled close button in a sheet, a menu item
 * nobody named. `a11yScan()` (see `test/helpers/A11yCheck.kt`) runs the same
 * scan against the scene as it is *right now*, and reports what it saw root by
 * root, so a test can look at an overlay while it is still open.
 *
 * ## What the desktop shows
 *
 * Measured on Compose Multiplatform 1.12 / skiko, in this harness:
 *
 * - A `ModalBottomSheet` opens its **own semantics root** — the task editor's
 *   priority sheet is a root of its own, next to the app window — and the scan
 *   walks it.
 * - An `AlertDialog` and a `Popup` do the same.
 *
 * The scan's per-root report is what makes the difference visible: a
 * violation is attributed to the window it was found in, so "the app window is
 * clean, the sheet is not" is a statement the test can make.
 *
 * ## What the scan reports
 *
 * Only clickables that are **on screen** count (see `A11yVisibilityRuleTest`).
 * The findings the tests below print are real: the priority sheet's radio
 * buttons and the profile dialog's colour swatches are visible, unlabelled
 * clickables. They are reported here verbatim rather than filtered out — and
 * they do not fail these tests, because the defect belongs to the screen, not
 * to the harness.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class A11yOverlayScanFlowTest {

    /**
     * A bottom sheet opens its own root, and the scan walks it while it is open.
     *
     * The task editor's priority sheet is a real `ModalBottomSheet`: on skiko it
     * is a second window with a second semantics root. The scan must reach it —
     * a scan that only walked the app window would report the sheet as clean
     * without ever having looked at it.
     */
    @Test
    fun the_scan_sees_a_bottom_sheet_in_its_own_root_while_it_is_open() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
            tasks(koin).given(due = today)
            tasks(koin).open("Buy milk")
            awaitTag(TestTags.TASK_EDITOR_PRIORITY_ROW)
            // The row is below the fold on a 1024x768 window: a click that lands
            // outside the window opens no sheet at all.
            clickTagScrolled(TestTags.TASK_EDITOR_PRIORITY_ROW)
            awaitTag(TestTags.PRIORITY_OPTION_NONE)

            val scan = a11yScan()

            assertTrue(
                scan.rootsScanned >= 2,
                "the priority sheet should have opened its own semantics root; " +
                    "the scan walked ${scan.rootsScanned} root(s).",
            )
            val sheetRoot = scan.roots.last()
            assertTrue(
                TestTags.PRIORITY_OPTION_NONE in sheetRoot.tags,
                "the scan did not see the sheet's own rows; the sheet root carries " +
                    "${sheetRoot.tags}.",
            )
            assertTrue(
                sheetRoot.texts.any { it.contains("priority") || it.contains("Приоритет") },
                "the scan did not see the sheet's title; the sheet root carries " +
                    "${sheetRoot.texts}.",
            )

            // The app window behind the sheet has nothing to report; everything
            // the scan found belongs to the sheet's own root.
            assertTrue(
                scan.roots.first().violations.isEmpty(),
                "the app window behind the sheet has clickable nodes that announce " +
                    "nothing:\n" + scan.roots.first().violations.joinToString("\n"),
            )
            if (sheetRoot.violations.isNotEmpty()) {
                println(
                    "A11y findings in the priority sheet (root ${sheetRoot.index}), " +
                        "reported by the scan that ran while the sheet was open:\n" +
                        sheetRoot.violations.joinToString("\n"),
                )
            }
        }

    /**
     * A `Popup` opens its own root, and the scan walks it while it is open.
     *
     * The notes sort menu is a `DropdownMenu`, which is a `Popup`: on skiko it
     * is a second window with a second semantics root. The scan must reach it —
     * a scan that only walked the app window would report the menu as clean
     * without ever having looked at it.
     */
    @Test
    fun the_scan_sees_a_popup_menu_in_its_own_root_while_it_is_open() =
        runDesktopAppTest(checkA11y = true) {
            tapTab("Notes")
            clickContentDescription("Sort")

            val scan = a11yScan()

            assertTrue(
                scan.rootsScanned >= 2,
                "the sort menu is a Popup and should have opened its own semantics " +
                    "root; the scan walked ${scan.rootsScanned} root(s). If a " +
                    "Compose upgrade folds the popup into the app window, the " +
                    "per-root report in A11yCheck no longer has a second root to " +
                    "report — update this test rather than deleting it.",
            )
            val popupRoot = scan.roots.last()
            assertTrue(
                popupRoot.texts.any { it.contains("Recently updated") },
                "the scan did not see the sort menu's own items; it saw " +
                    "${popupRoot.texts}. The scan walked the app window and " +
                    "missed the popup's root.",
            )
            assertTrue(
                scan.violations.isEmpty(),
                "the scan found clickable nodes that announce nothing while the " +
                    "sort menu was open:\n" + scan.violations.joinToString("\n"),
            )
        }

    /**
     * An `AlertDialog` opens its own root, and the scan sees it — and stops
     * seeing it once the dialog is dismissed.
     *
     * The second half is the point of the whole exercise: the end-of-test scan
     * runs *after* the body, and by then the dialog is gone. A scan taken
     * while the dialog was open is the only chance to look at it.
     *
     * The profile-create dialog carries a row of eight colour swatches with no
     * label of their own. That is a real accessibility finding, not a test
     * artifact: it is reported here, verbatim, rather than filtered out — and
     * it is not allowed to fail this test either, because the finding belongs
     * to the screen, not to the harness.
     */
    @Test
    fun the_scan_sees_a_dialog_in_its_own_root_while_it_is_open() =
        runDesktopAppTest(checkA11y = true) {
            openDrawer()
            clickContentDescriptionScrolled("Profiles")
            awaitTag(TestTags.PROFILE_CREATE_BUTTON)
            clickTag(TestTags.PROFILE_CREATE_BUTTON)
            awaitTag(TestTags.Dialog.CONFIRM)

            val scan = a11yScan()

            assertTrue(
                scan.rootsScanned >= 2,
                "the profile dialog should have opened its own semantics root; " +
                    "the scan walked ${scan.rootsScanned} root(s).",
            )
            val dialogRoot = scan.roots.last()
            assertTrue(
                TestTags.Dialog.CONFIRM in dialogRoot.tags,
                "the scan did not see the dialog's confirm button; the dialog root " +
                    "carries ${dialogRoot.tags}.",
            )
            assertTrue(
                dialogRoot.texts.any { it.contains("New Profile") },
                "the scan did not see the dialog's title; the dialog root carries " +
                    "${dialogRoot.texts}.",
            )

            // The app window behind the dialog has nothing to report; everything
            // the scan found belongs to the dialog's own root.
            assertTrue(
                scan.roots.first().violations.isEmpty(),
                "the app window behind the dialog has clickable nodes that announce " +
                    "nothing:\n" + scan.roots.first().violations.joinToString("\n"),
            )
            if (dialogRoot.violations.isNotEmpty()) {
                println(
                    "A11y findings in the profile-create dialog (root " +
                        "${dialogRoot.index}), reported by the scan that ran while " +
                        "the dialog was open:\n" +
                        dialogRoot.violations.joinToString("\n"),
                )
            }

            // Dismiss it, and the scan no longer sees it — which is exactly why
            // the scan has to be run from inside the test body. The profile
            // dialog's own buttons are labelled, not tagged, so it is closed by
            // the label the user reads.
            clickText("Cancel")
            val afterDismiss = a11yScan()
            assertTrue(
                TestTags.Dialog.CONFIRM !in afterDismiss.tagsSeen,
                "the dialog is still in the tree after it was dismissed; the " +
                    "end-of-test scan would have seen it after all, and this " +
                    "test would not be proving anything.",
            )
            assertTrue(
                afterDismiss.textsSeen.none { it.contains("New Profile") },
                "the dismissed dialog's title is still in the tree: " +
                    "${afterDismiss.textsSeen}",
            )
        }

    private companion object {
        /** A fixed "today" so the seeded task is on the agenda the test boots into. */
        val CLOCK: FakeClock = FakeClock(Instant.parse("2026-09-16T10:00:00Z"))
        val today: LocalDate = LocalDate(2026, 9, 16)
    }
}
