package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.TaskDocument
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskWorkflowState
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.testTask
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitText
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.scrollToTag
import com.singularity.todo.test.helpers.tapTab
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.koin.core.Koin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Desktop automation for `TASK-STATE-01..03` — see
 * `scripts/src/traceability/scenarios/tasks/state/`.
 *
 * The `@DisplayName` above is the linkage: each id is the **first token** of
 * the string.
 *
 * ## What is driven
 *
 * The real state sheet, through the real intent and the real repository. The
 * state is read back from the database where the claim is about storage, and
 * from the screen where the claim is about what the user is told.
 *
 * ## Why the a11y pass is off
 *
 * The state sheet is a platform bottom sheet, and the harness's a11y pass does
 * not walk its separate window. That is a property of the harness, not of this
 * flow.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskStateScenarioTest {

    @Test
    @DisplayName("TASK-STATE-01 a workflow state is chosen on the task screen")
    fun a_workflow_state_is_chosen_on_the_task_screen() =
        runDesktopAppTest(clock = CLOCK) { koin ->
            seed(koin, TITLE, due = today)
            openEditor(TITLE)

            // The control states the current state before it is opened.
            scrollToStateRow()
            assertTextDisplayed("No state")

            clickTag(TestTags.TASK_EDITOR_WORKFLOW_STATE_ROW)
            awaitTag(TestTags.WORKFLOW_STATE_OPTION_IN_PROGRESS)
            clickTag(TestTags.WORKFLOW_STATE_OPTION_IN_PROGRESS)

            // The screen shows the chosen state, and the task is still open.
            awaitText("In progress")
            val stored = storedState(koin, TITLE)
            assertEquals(TaskWorkflowState.InProgress, stored)
        }

    @Test
    @DisplayName("TASK-STATE-02 a workflow state survives leaving and reopening the task")
    fun a_workflow_state_survives_leaving_and_reopening_the_task() =
        runDesktopAppTest(clock = CLOCK) { koin ->
            val id = seed(koin, TITLE, due = today)
            openEditor(TITLE)

            scrollToStateRow()
            clickTag(TestTags.TASK_EDITOR_WORKFLOW_STATE_ROW)
            awaitTag(TestTags.WORKFLOW_STATE_OPTION_IN_PROGRESS)
            clickTag(TestTags.WORKFLOW_STATE_OPTION_IN_PROGRESS)
            awaitText("In progress")

            // Leave the screen, then come back to the same task. The task
            // editor's back arrow carries a content description, not a tag.
            onAllNodesWithContentDescription("Назад").onFirst().performClick()
            awaitTag(TestTags.taskItem(TITLE)).performClick()
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

            scrollToStateRow()
            assertTextDisplayed("In progress")
            assertEquals(
                TaskWorkflowState.InProgress,
                withTimeout(TIMEOUT_MS) { koin.get<TaskRepository>().get(id)?.workflowState },
                "the state must be on the task, not in the screen that was open",
            )
        }

    @Test
    @DisplayName("TASK-STATE-03 a completed task keeps its workflow state and cannot change it")
    fun a_completed_task_keeps_its_workflow_state_and_cannot_change_it() =
        runDesktopAppTest(clock = CLOCK) { koin ->
            val id = seed(
                koin,
                TITLE,
                due = today,
                workflowState = TaskWorkflowState.InProgress,
                completed = true,
            )
            openEditor(TITLE)

            // The state of the finished work is still readable.
            scrollToStateRow()
            assertTextDisplayed("In progress")

            // The control is inert: pressing it opens nothing.
            clickTag(TestTags.TASK_EDITOR_WORKFLOW_STATE_ROW)
            assertTagNotExists(TestTags.WORKFLOW_STATE_OPTION_OPEN)

            assertEquals(
                TaskWorkflowState.InProgress,
                withTimeout(TIMEOUT_MS) { koin.get<TaskRepository>().get(id)?.workflowState },
                "completing a task must not reset the state it finished in",
            )
        }

    // ── helpers ────────────────────────────────────────────────────────────

    private suspend fun seed(
        koin: Koin,
        title: String,
        due: LocalDate?,
        workflowState: TaskWorkflowState = TaskWorkflowState.Open,
        completed: Boolean = false,
    ): TaskId {
        val userId = koin.get<ProfileAwareCurrentUser>().scopedUserId.value
        val task = testTask(
            id = TaskId(title),
            title = title,
            dueDate = due,
            completedAt = if (completed) FIXED_NOW else null,
            userId = userId,
        ).copy(workflowState = workflowState)
        koin.get<TaskRepository>().upsert(TaskDocument.of(task))
        return task.id
    }

    private fun DesktopComposeUiTest.openEditor(title: String) {
        tapTab("Today")
        awaitTag(TestTags.taskItem(title)).performClick()
        awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)
    }

    private fun DesktopComposeUiTest.scrollToStateRow() {
        scrollToTag(TestTags.TASK_EDITOR_WORKFLOW_STATE_ROW)
        assertTagDisplayed(TestTags.TASK_EDITOR_WORKFLOW_STATE_ROW)
    }

    private suspend fun storedState(koin: Koin, title: String): TaskWorkflowState? =
        withTimeout(TIMEOUT_MS) {
            koin.get<TaskRepository>().observeAll()
                .first { tasks -> tasks.any { it.title == title } }
                .first { it.title == title }
                .workflowState
        }

    private companion object {
        const val TITLE = "Plan sprint"
        const val TIMEOUT_MS = 10_000L

        /** Mid-month, so no assertion in this file straddles a boundary. */
        val FIXED_NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
        val CLOCK: FakeClock = FakeClock(FIXED_NOW)
        val today: LocalDate = LocalDate(2026, 9, 16)
    }
}
