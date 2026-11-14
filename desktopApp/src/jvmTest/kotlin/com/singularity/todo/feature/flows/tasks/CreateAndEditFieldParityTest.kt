package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.DesktopShell
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.clickContentDescription
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tagExists
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.typeIntoTag
import com.singularity.todo.test.helpers.unclippedBoundsOfTag
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Create and Edit are two entry points into one editor, so the attribute rows they
 * draw are the same rows in the same order.
 *
 * The renderer was already shared; the divergence was in the call sites — Create
 * passed a handful of bundles and the detail passed the rest, so a task could not be
 * created complete and the same task had a different shape depending on how it was
 * opened (UXF-24).
 *
 * The assertion is against [SHARED_ROWS], not Create against Edit. Comparing the two
 * surfaces to each other would stay green when a row disappeared from **both** — the
 * defect this test exists for is a field a person cannot reach, and two screens that
 * both lost it are still wrong. Each surface is therefore checked against the expected
 * contract, and against the other, so a one-sided row and a missing row both fail.
 *
 * The rows listed in [ONE_SIDED] are deliberately one-sided, and each has a reason the
 * screen would show if the row appeared on both. They are excluded from both sides by
 * name; a row that becomes one-sided later fails here until it is either drawn on both
 * surfaces or written into the list with its reason.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class CreateAndEditFieldParityTest {
    @Test
    fun create_and_edit_expose_the_same_rows_in_the_same_order() =
        runDesktopAppTest(checkA11y = true) {
            tapTab("Inbox")
            clickContentDescription(DesktopShell.FAB_ADD_TASK)
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)
            val createRows = rowsOnScreen()

            // Round trip: save the task, then open it. Create lands back on the list
            // it was opened from, which is where the row for the new task is.
            typeIntoTag(TestTags.TASK_EDITOR_TITLE_INPUT, "Parity task")
            awaitTag(TestTags.TASK_EDITOR_SAVE).performClick()
            awaitTag(TestTags.taskItem("Parity task")).performClick()
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)
            val editRows = rowsOnScreen()

            assertEquals(
                SHARED_ROWS,
                createRows - ONE_SIDED,
                "Create does not draw the shared rows, in this order. It drew $createRows.",
            )
            assertEquals(
                SHARED_ROWS,
                editRows - ONE_SIDED,
                "Edit does not draw the shared rows, in this order. It drew $editRows.",
            )
        }

    /**
     * The editor's own rows, top to bottom, as the composed screen draws them.
     *
     * The positions are read from the tree rather than from a constant list, so the
     * comparison is about the screen and not about this file's idea of it. The bounds
     * are unclipped: a row below the fold still has a position, and the claim is about
     * the arrangement, not about what happens to fit.
     */
    private fun DesktopComposeUiTest.rowsOnScreen(): List<String> =
        EDITOR_ROWS
            .filter { tag -> tagExists(tag) }
            .sortedBy { tag -> unclippedBoundsOfTag(tag).top.value }

    private companion object {
        /**
         * The rows both surfaces must draw, in the editor's own order.
         *
         * The expected list, not a filter over what is on the screen: a row that is
         * missing from both surfaces has to fail here, and comparing two screens that
         * both lost it would not.
         *
         * The order is the renderer's: the priority, the estimate, the date, the
         * reminder, the deadline, the start date, the project, the tags, the
         * recurrence and the pin.
         */
        val SHARED_ROWS: List<String> =
            listOf(
                TestTags.TASK_EDITOR_PRIORITY_ROW,
                TestTags.TASK_EDITOR_ESTIMATE_ROW,
                TestTags.TASK_EDITOR_DUE_ROW,
                TestTags.TASK_EDITOR_START_DATE_ROW,
                TestTags.TASK_EDITOR_PROJECT_ROW,
                TestTags.TASK_EDITOR_TAGS_ROW,
                TestTags.TASK_EDITOR_RECURRENCE_ROW,
                TestTags.TASK_EDITOR_PIN_ROW,
            )

        /** Every attribute row the editor can draw. The hero (title, description) and
         * the chrome (the bar, the save button) are not rows and are not compared. */
        val EDITOR_ROWS: List<String> =
            listOf(
                TestTags.TASK_EDITOR_CHECKLIST_ROW,
                TestTags.TASK_EDITOR_ATTACHMENTS_ROW,
                TestTags.TASK_EDITOR_WORKFLOW_STATE_ROW,
                TestTags.TASK_EDITOR_PRIORITY_ROW,
                TestTags.TASK_EDITOR_ESTIMATE_ROW,
                TestTags.TASK_EDITOR_DUE_ROW,
                TestTags.TASK_EDITOR_REMINDER_ROW,
                TestTags.TASK_EDITOR_DEADLINE_ROW,
                TestTags.TASK_EDITOR_START_DATE_ROW,
                TestTags.TASK_EDITOR_PROJECT_ROW,
                TestTags.TASK_EDITOR_TAGS_ROW,
                TestTags.TASK_EDITOR_RECURRENCE_ROW,
                TestTags.TASK_EDITOR_PIN_ROW,
                TestTags.TASK_EDITOR_MORE_PARAMETERS_ROW,
            )

        /**
         * Rows that are on one surface and not the other, with the reason.
         *
         * - The checklist and the attachments: the detail draws them as sections below
         *   the fields, so a row for them as well would be two ways into one list.
         * - The reminder, the deadline and the workflow state: these wait on the
         *   scheduling and empty-section contracts (#2876, #2880), which own where
         *   those facts live on a task that does not exist yet.
         * - `+ Ещё`: the detail's way into its hidden sections. Create has nothing
         *   hidden, so it has nothing to list.
         */
        val ONE_SIDED: Set<String> =
            setOf(
                TestTags.TASK_EDITOR_CHECKLIST_ROW,
                TestTags.TASK_EDITOR_ATTACHMENTS_ROW,
                TestTags.TASK_EDITOR_REMINDER_ROW,
                TestTags.TASK_EDITOR_DEADLINE_ROW,
                TestTags.TASK_EDITOR_WORKFLOW_STATE_ROW,
                TestTags.TASK_EDITOR_MORE_PARAMETERS_ROW,
            )
    }
}
