@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.agenda

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.clickTagFirst
import com.singularity.todo.test.helpers.countNodesWithTagInAnyRoot
import com.singularity.todo.test.helpers.runDesktopAppTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The agenda's infrequent top-bar actions live in an overflow menu, and the menu
 * says what they are.
 *
 * ## What this pins
 *
 * The agenda bar used to carry three unlabelled icons: saved views, "task on
 * several dates" and "save current agenda". Two of them moved into an overflow
 * menu, which is only an improvement if the rows are *labelled* and *reachable*:
 *
 * - the two actions are not on the bar any more (their tags are in no root while
 *   the menu is closed),
 * - the menu's rows carry the two actions' tags and a visible label of their own,
 * - a row still reaches its behaviour — the multi-date row opens the picker it
 *   always opened — and the menu is out of the way once it did.
 *
 * The menu opens its own window on desktop, so its rows are counted across every
 * root ([countNodesWithTagInAnyRoot]) rather than in the app's own window.
 *
 * The wording of the labels belongs to
 * `AgendaTopBarStringsResourceTest` — this test says a row shows *a* label, and
 * that test says which one.
 */
@Tag("slow")
@Tag("slow:compose")
class AgendaTopBarOverflowFlowTest {
    /**
     * The two infrequent actions are in the menu, under labels.
     *
     * A menu row without a label is exactly the defect the overflow was meant to
     * fix, so the row has to carry text of its own — the wording is not this
     * test's business.
     */
    @Test
    fun the_infrequent_actions_are_reachable_from_a_labeled_overflow_menu() =
        runDesktopAppTest(checkA11y = true) {
            // Closed menu: the two actions are not on the bar.
            assertEquals(
                0,
                countNodesWithTagInAnyRoot(TestTags.AGENDA_SAVE_CURRENT_BUTTON),
                "the save-current action is still a top-bar button; it belongs in the overflow menu",
            )
            assertEquals(
                0,
                countNodesWithTagInAnyRoot(TestTags.AGENDA_MULTI_DATE_BUTTON),
                "the multi-date action is still a top-bar button; it belongs in the overflow menu",
            )

            clickTag(TestTags.AGENDA_OVERFLOW_BUTTON)

            assertEquals(
                1,
                countNodesWithTagInAnyRoot(TestTags.AGENDA_SAVE_CURRENT_BUTTON),
                "the overflow menu does not carry the save-current row",
            )
            assertEquals(
                1,
                countNodesWithTagInAnyRoot(TestTags.AGENDA_MULTI_DATE_BUTTON),
                "the overflow menu does not carry the multi-date row",
            )

            assertTrue(
                labelOf(TestTags.AGENDA_SAVE_CURRENT_BUTTON).isNotEmpty(),
                "the save-current row has no visible label; an icon alone says nothing",
            )
            assertTrue(
                labelOf(TestTags.AGENDA_MULTI_DATE_BUTTON).isNotEmpty(),
                "the multi-date row has no visible label; an icon alone says nothing",
            )
        }

    /**
     * A row of the menu still reaches the behaviour behind it.
     *
     * The multi-date action had no test at all before it moved — this is the one
     * that says the move did not break it.
     */
    @Test
    fun the_multi_date_row_still_opens_the_date_picker() =
        runDesktopAppTest(checkA11y = true) {
            clickTag(TestTags.AGENDA_OVERFLOW_BUTTON)
            clickTagFirst(TestTags.AGENDA_MULTI_DATE_BUTTON)

            awaitTag(TestTags.MultiDatePicker.CANCEL)

            // The menu is closed before the row's action runs, so it does not stay
            // open over the picker it opened.
            awaitTagGone(TestTags.AGENDA_MULTI_DATE_BUTTON)
        }
}

/**
 * The label the row with [tag] shows, or `""` when it shows none.
 *
 * The row's own semantics text, not a string this test knows: the wording
 * belongs to the resource-bundle test.
 */
private fun DesktopComposeUiTest.labelOf(tag: String): String {
    val node = awaitTag(tag).fetchSemanticsNode()
    val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
    return text.joinToString(" ") { it.text }
}
