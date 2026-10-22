package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.DesktopShell
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clickContentDescription
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.clickTagFirst
import com.singularity.todo.test.helpers.clickTagScrolled
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.scrollToTag
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop automation for scenario `TASK-REC-01` — see
 * `scripts/src/traceability/scenarios/tasks/recurrence/TASK-REC-01.yaml`.
 *
 * The `@DisplayName` above is the linkage. The id is the **first token** of the
 * string, and that strictness is the contract: the traceability scanner rejects
 * an id anywhere else rather than guessing, because a regex that starts
 * tolerating prose eventually matches an id that merely gets *mentioned* in a
 * test name. Renaming the method is safe; reformatting the display name is not,
 * and `just trace-validate` says so.
 *
 * ## Why this test is in the scenario system at all
 *
 * Coverage could previously only be answered at class granularity, and the
 * answer was 259 test classes — none of which correspond to a user-visible
 * behaviour. A scenario is that behaviour, verified per *target*: one
 * `TASK-REC-01` can be `●` on desktop and `○` on Android, which is a statement
 * about the product rather than about the file layout.
 *
 * ## What the desktop tier can verify here, measured not assumed
 *
 * The recurrence control is exposed by the **composer** only.
 * `TaskDetailContent` passes `recurrenceCallbacks = null`, so the row is
 * deliberately absent from the editor reached by tapping a task — this test
 * failed at exactly that selector before the scoping below was written down.
 * And the composer cannot be given a pre-existing recurrence, because the
 * recurrence is chosen *in* the composer.
 *
 * Choosing a frequency used to be out of reach here. The claim was that the
 * picker is a `ModalBottomSheet`, that Compose Multiplatform renders sheets
 * into a separate semantics root on desktop, and that a selector inside it is
 * therefore invisible to a JVM Compose test. The claim was **measured on a
 * broken row**: the editor opened a sheet state that nothing rendered, so
 * nothing inside the sheet was found, and a bug was read as a platform fact.
 *
 * The measurement today, on this repository:
 *
 * - the picker opens **its own window**, so the app's own tree does not change
 *   and the picker's nodes are not in it;
 * - the picker's nodes **are** in the scene, and the cross-root search finds
 *   them — `onNodeWithTag` and `onAllNodesWithTag` look across the roots;
 * - so `TestTags.RECURRENCE_OPTION_*` and `TestTags.SHEET_CONFIRM` are
 *   assertable from the desktop tier.
 *
 * `SheetReachabilityTest` and `PopupReachabilityTest` pin those claims.
 *
 * ## What this test does and does not claim
 *
 * It covers the composer half of the journey: the control is there, the user
 * chooses Daily, and the row reports the rule. The second half of the scenario
 * — saving the task, completing it once, and finding the next occurrence dated
 * a day later — is a journey through the agenda, and the Maestro flow tagged
 * `scenario:TASK-REC-01` still drives it on the device.
 *
 * A `●` in the matrix should mean "a real user behaviour is verified here", not
 * "a selector was found" — and the choice of Daily is a behaviour, not a
 * selector.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskRecurrenceScenarioTest {

    @Test
    @DisplayName("TASK-REC-01 the composer applies a daily recurrence")
    fun the_composer_applies_a_daily_recurrence() = runDesktopAppTest(checkA11y = true) {
        // Desktop renders a plain FAB carrying only a contentDescription —
        // `TestTags.TASKS_FAB` is the Android shell's, per CreateTaskFlowTest.
        clickContentDescription(DesktopShell.FAB_ADD_TASK)

        awaitTag(TestTags.TASK_EDITOR_RECURRENCE_ROW)

        // The attribute list scrolls, and the recurrence row sits below the fold
        // of the 1024x768 test window: a node that is composed but out of view
        // reads as "not displayed", which is a different failure from "not there".
        scrollToTag(TestTags.TASK_EDITOR_RECURRENCE_ROW)

        // The label is the state the user reads, so asserting it is what makes
        // this a behaviour check rather than a selector smoke test: a row
        // rendering the right tag while reporting a stale rule would pass a
        // presence-only assertion.
        assertTextDisplayed("No repeat")

        // The picker opens its own window on desktop; its content is in the
        // scene, and the cross-root search finds it.
        clickTagScrolled(TestTags.TASK_EDITOR_RECURRENCE_ROW)
        awaitTag(TestTags.RECURRENCE_OPTION_DAILY)
        clickTag(TestTags.RECURRENCE_OPTION_DAILY)

        // The sheet does not dismiss on a selection: the ✓ commits the pending
        // rule, and the sheet is composed in two windows while it animates, so
        // the button is in the tree twice and the first copy is pressed.
        clickTagFirst(TestTags.SHEET_CONFIRM)
        awaitTagGone(TestTags.RECURRENCE_OPTION_DAILY)

        // The row now reports the rule the user chose. This is the step that was
        // unreachable from this tier before the sheet's chrome was fixed.
        assertTagDisplayed(TestTags.TASK_EDITOR_RECURRENCE_ROW)
        assertTextDisplayed("Repeats")
    }
}
