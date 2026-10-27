package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.flows.agenda.AgendaStateScenarioSupport.CLOCK
import com.singularity.todo.feature.flows.agenda.AgendaStateScenarioSupport.VIEW_NAME
import com.singularity.todo.feature.flows.agenda.AgendaStateScenarioSupport.createInProgressView
import com.singularity.todo.feature.flows.agenda.AgendaStateScenarioSupport.openSavedView
import com.singularity.todo.feature.flows.agenda.AgendaStateScenarioSupport.seed
import com.singularity.todo.feature.tasks.domain.model.TaskWorkflowState
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop automation for `AGENDA-STATE-01` — see
 * `scripts/src/traceability/scenarios/agenda/state/AGENDA-STATE-01.yaml`.
 *
 * The `@DisplayName` is the linkage: the id is its **first token**. One
 * scenario per file — the follow-up scenario lives in
 * `AgendaStateFollowScenarioTest`.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class AgendaStateFilterScenarioTest {

    @Test
    @DisplayName("AGENDA-STATE-01 a saved view shows only the tasks in the chosen state")
    fun a_saved_view_shows_only_the_tasks_in_the_chosen_state() =
        runDesktopAppTest(clock = CLOCK) { koin ->
            seed(koin, "Started task", TaskWorkflowState.InProgress)
            seed(koin, "Idle task", TaskWorkflowState.Open)
            createInProgressView(koin)

            tapTab("Inbox")
            openSavedView(this, VIEW_NAME)

            // Only the in-progress task is in the section; the other task is
            // not somewhere else on the screen either.
            assertTagDisplayed(TestTags.agendaSection("In progress"))
            assertTagDisplayed(TestTags.taskItem("Started task"))
            assertTagNotExists(TestTags.taskItem("Idle task"))
        }
}
