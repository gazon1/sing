package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.DesktopComposeUiTest
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
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.scrollToTag
import com.singularity.todo.test.helpers.tapTab
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import org.koin.core.Koin
import kotlin.time.Instant

/**
 * Shared fixtures for the three `TASK-STATE` scenarios.
 *
 * The traceability rule is one scenario per test file: display-name
 * annotations carrying more than one scenario id in one file are reported as
 * several scenarios claimed by one file. The scenarios therefore live in
 * `TaskStateChosenScenarioTest`, `TaskStateSurvivesScenarioTest` and
 * `TaskStateCompletedScenarioTest`, and everything they share lives here,
 * where no scenario is claimed.
 *
 * ## What is driven
 *
 * The real state sheet, through the real intent and the real repository. The
 * state is read back from the database where the claim is about storage, and
 * from the screen where the claim is about what the user is told.
 */
internal object TaskStateScenarioSupport {

    const val TITLE = "Plan sprint"
    const val TIMEOUT_MS = 10_000L

    /** Mid-month, so no assertion in these scenarios straddles a boundary. */
    val FIXED_NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
    val CLOCK: FakeClock = FakeClock(FIXED_NOW)
    val today: LocalDate = LocalDate(2026, 9, 16)

    suspend fun seed(
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

    fun openEditor(ui: DesktopComposeUiTest, title: String) {
        ui.tapTab("Today")
        ui.awaitTag(TestTags.taskItem(title)).performClick()
        ui.awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)
    }

    fun scrollToStateRow(ui: DesktopComposeUiTest) {
        ui.scrollToTag(TestTags.TASK_EDITOR_WORKFLOW_STATE_ROW)
        ui.assertTagDisplayed(TestTags.TASK_EDITOR_WORKFLOW_STATE_ROW)
    }

    suspend fun storedState(koin: Koin, title: String): TaskWorkflowState? =
        withTimeout(TIMEOUT_MS) {
            koin.get<TaskRepository>().observeAll()
                .first { tasks -> tasks.any { it.title == title } }
                .first { it.title == title }
                .workflowState
        }
}
