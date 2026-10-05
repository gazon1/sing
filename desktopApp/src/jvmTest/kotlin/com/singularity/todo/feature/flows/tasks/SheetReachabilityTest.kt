package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.DesktopShell
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.assertTextNotExists
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.clickContentDescription
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.countNodesWithTagInAnyRoot
import com.singularity.todo.test.helpers.mainTreeSize
import com.singularity.todo.test.helpers.runDesktopAppTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tier rule from `Maestro/CONVENTIONS.md` — a `ModalBottomSheet` is not
 * reachable from a desktop JVM test — pinned as a test so it fails loudly if a
 * Compose upgrade makes sheets reachable.
 *
 * This deliberately asserts that the sheet is **unreachable**. It is not a
 * skipped "cannot test" placeholder: the behaviour is the point, and a future
 * Compose release that renders a sheet into the main window would break this test
 * and send the author back to the convention, which is the correct outcome. The
 * alternative — leaving the convention as prose — is how it silently went stale
 * the first time.
 *
 * The "click works" half matters as much as the "sheet is invisible" half. A test
 * that only asserted non-reachability would pass just as happily against an
 * editor where the click did nothing at all, which is the failure mode that costs
 * the hour.
 *
 * @see Maestro/CONVENTIONS.md "Choosing a Tier: Sheets Are Maestro-Only"
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
class SheetReachabilityTest {

    @Test
    @DisplayName("a ModalBottomSheet stays outside the desktop semantics tree")
    fun a_sheet_is_unreachable_but_the_click_is_real() = runDesktopAppTest {
        clickContentDescription(DesktopShell.FAB_ADD_TASK)
        awaitTag(TestTags.TASK_EDITOR_RECURRENCE_ROW)
        assertTextDisplayed("Not pinned")

        // Control: a click that changes state in the main tree, proving clicks
        // reach composables in this editor at all.
        clickTag(TestTags.TASK_EDITOR_PIN_ROW)
        assertTextDisplayed("Pinned")
        assertTextNotExists("Not pinned")

        val beforeSheet = mainTreeSize()
        clickTag(TestTags.TASK_EDITOR_RECURRENCE_ROW)

        // The sheet adds nothing at all: not a tag, not its own text, no new node.
        // Equality (not just absence) is the strong claim — a Popup-based sheet
        // would still add a node, and the convention would then be wrong.
        assertEquals(
            beforeSheet,
            mainTreeSize(),
            "the main semantics tree grew when the sheet opened; if a sheet is " +
                "now reachable, update Maestro/CONVENTIONS.md and the coverage " +
                "matrix — the Android column may no longer be the only tier",
        )
        assertEquals(
            0,
            countNodesWithTagInAnyRoot(TestTags.RECURRENCE_OPTION_DAILY),
            "a sheet option was found by the cross-root search; see the convention",
        )
        assertTrue(
            mainTreeSize() > 0,
            "the main tree should never be empty — an empty one would make the " +
                "assertions above vacuous",
        )
    }
}
