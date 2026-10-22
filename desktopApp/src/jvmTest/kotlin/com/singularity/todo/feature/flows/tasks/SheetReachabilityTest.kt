package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.DesktopShell
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.assertTextNotExists
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.clickContentDescription
import com.singularity.todo.test.helpers.clickTagScrolled
import com.singularity.todo.test.helpers.countNodesWithTagInAnyRoot
import com.singularity.todo.test.helpers.countNodesWithTagInMainRoot
import com.singularity.todo.test.helpers.mainTreeSize
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.scrollToTag
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tier rule from `Maestro/CONVENTIONS.md` — a `ModalBottomSheet` opens its own
 * window on desktop — pinned as a test so it fails loudly if a Compose upgrade changes
 * what a selector sees.
 *
 * ## What changed, and why this test used to be wrong
 *
 * The rule used to be "a sheet cannot be asserted from a desktop JVM test". It was
 * measured on the task editor's recurrence row, and the measurement was taken while
 * that row did nothing at all: the row opened a sheet state that nothing rendered.
 * The sheet never opened, so nothing inside it was found, and the conclusion drawn was
 * a platform fact. It was a bug wearing the costume of a platform fact.
 *
 * The editor's rows work now — one state, opened by the row and rendered by the host —
 * and the sheet's content is reachable from the desktop tier after all. What is left
 * of the platform fact is the part that is true: the sheet opens **its own window**, so
 * the main tree does not change and the sheet's nodes are not in it.
 *
 * The three claims below are the ones worth pinning, because a Compose upgrade that
 * folds the sheet back into the main tree would change what every selector sees:
 *
 * 1. the main tree does not change when the sheet opens;
 * 2. the sheet's option is **not** in the main tree;
 * 3. the sheet's option **is** in the scene — a second root.
 *
 * @see Maestro/CONVENTIONS.md "Choosing a Tier: A Sheet Opens Its Own Window"
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class SheetReachabilityTest {

    @Test
    @DisplayName("a ModalBottomSheet opens its own window, and the main tree does not change")
    fun a_sheet_opens_its_own_window_and_the_main_tree_does_not_change() = runDesktopAppTest {
        clickContentDescription(DesktopShell.FAB_ADD_TASK)
        awaitTag(TestTags.TASK_EDITOR_RECURRENCE_ROW)

        // The editor's attribute list scrolls, and the pin row sits below the fold
        // of the 1024x768 test window — a node that is composed but out of view is
        // "not displayed", which is a different failure from "not there".
        scrollToTag(TestTags.TASK_EDITOR_PIN_ROW)
        assertTextDisplayed("Not pinned")

        // Control: a click that changes state in the main tree, proving clicks
        // reach composables in this editor at all.
        clickTagScrolled(TestTags.TASK_EDITOR_PIN_ROW)
        assertTextDisplayed("Pinned")
        assertTextNotExists("Not pinned")

        val beforeSheet = mainTreeSize()
        clickTagScrolled(TestTags.TASK_EDITOR_RECURRENCE_ROW)

        // The sheet animates in, so the wait comes before the measurements: a
        // one-shot count right after the click races the sheet's own window and
        // measures a scene that has not opened yet.
        awaitTag(TestTags.RECURRENCE_OPTION_DAILY)

        // The main tree is unchanged: the sheet is not an overlay inside it.
        assertEquals(
            beforeSheet,
            mainTreeSize(),
            "the main semantics tree grew when the sheet opened; if a sheet is " +
                "now reachable, update Maestro/CONVENTIONS.md and the coverage " +
                "matrix — the Android column may no longer be the only tier",
        )
        assertEquals(
            0,
            countNodesWithTagInMainRoot(TestTags.RECURRENCE_OPTION_DAILY),
            "the sheet's option was found in the main tree; the sheet is supposed " +
                "to open its own window",
        )
        assertEquals(
            1,
            countNodesWithTagInAnyRoot(TestTags.RECURRENCE_OPTION_DAILY),
            "the sheet's option was not found in the scene; a sheet opens its own " +
                "window, and that window is part of the scene",
        )
        assertTrue(
            mainTreeSize() > 0,
            "the main tree should never be empty — an empty one would make the " +
                "assertions above vacuous",
        )
    }
}
