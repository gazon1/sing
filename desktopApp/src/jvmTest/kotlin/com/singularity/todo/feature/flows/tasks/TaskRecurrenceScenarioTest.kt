package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.DesktopShell
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.clickContentDescription
import com.singularity.todo.test.helpers.runDesktopAppTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop automation for scenario `TASK-REC-01` — see
 * `infra/kiwi/scenarios/tasks/recurrence/TASK-REC-01.yaml`.
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
 * ## What desktop can and cannot verify here, measured not assumed
 *
 * The recurrence control is exposed by the **composer** only.
 * `TaskDetailContent` passes `recurrenceCallbacks = null`, so the row is
 * deliberately absent from the editor reached by tapping a task — this test
 * failed at exactly that selector before the scoping below was written down.
 * And the composer cannot be given a pre-existing recurrence, because the
 * recurrence is chosen *in* the composer.
 *
 * Choosing a frequency is not reachable from a JVM Compose test either: the
 * picker is a `ModalBottomSheet`, which Compose Multiplatform renders into a
 * separate semantics root on desktop, so every selector inside it is invisible
 * to `onNodeWithTag`. (`ConfirmActionDialog` is a real `AlertDialog` and *is*
 * reachable — see `SavedAgendaEditFlowTest` — so this is specific to sheets.)
 * That half of the scenario is therefore covered on Android by the Maestro flow
 * tagged `scenario:TASK-REC-01`.
 *
 * So this test verifies the desktop-visible entry point of the flow — the
 * composer offers the control, and it reports that nothing repeats yet — rather
 * than pretending to cover the whole journey. A `●` in the matrix should mean
 * "a real user behaviour is verified here", not "a selector was found".
 *
 * One scenario may be claimed by exactly one test per target: two tests claiming
 * one scenario would make the matrix cell ambiguous, so the scanner rejects that
 * instead of merging results.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
class TaskRecurrenceScenarioTest {

    @Test
    @DisplayName("TASK-REC-01 the composer offers a recurrence control")
    fun the_composer_offers_a_recurrence_control() = runDesktopAppTest(checkA11y = true) {
        // Desktop renders a plain FAB carrying only a contentDescription —
        // `TestTags.TASKS_FAB` is the Android shell's, per CreateTaskFlowTest.
        clickContentDescription(DesktopShell.FAB_ADD_TASK)

        awaitTag(TestTags.TASK_EDITOR_RECURRENCE_ROW)
        assertTagDisplayed(TestTags.TASK_EDITOR_RECURRENCE_ROW)

        // The label is the state the user reads, so asserting it is what makes
        // this a behaviour check rather than a selector smoke test: a row
        // rendering the right tag while reporting a stale rule would pass a
        // presence-only assertion.
        assertTextDisplayed("No repeat")
    }
}
