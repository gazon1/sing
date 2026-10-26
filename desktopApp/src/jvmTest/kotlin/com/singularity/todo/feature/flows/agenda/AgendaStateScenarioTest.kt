package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaViewFactory
import com.singularity.todo.feature.agenda.domain.model.toSectionsJson
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.TaskDocument
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskWorkflowState
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.testTask
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.koin.core.Koin
import kotlin.test.Test
import kotlin.time.Instant

/**
 * Desktop automation for `AGENDA-STATE-01..02` — see
 * `scripts/src/traceability/scenarios/agenda/state/`.
 *
 * The `@DisplayName` above is the linkage: each id is the **first token** of
 * the string.
 *
 * ## What is driven
 *
 * A saved view is seeded through the repository, and the results screen is
 * opened by tapping its card — the same path a user takes. The view stores a
 * selector, so the second scenario changes the *task* and reopens the same,
 * unedited view: that is the claim.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class AgendaStateScenarioTest {

    @Test
    @DisplayName("AGENDA-STATE-01 a saved view shows only the tasks in the chosen state")
    fun a_saved_view_shows_only_the_tasks_in_the_chosen_state() =
        runDesktopAppTest(clock = CLOCK) { koin ->
            seed(koin, "Started task", TaskWorkflowState.InProgress)
            seed(koin, "Idle task", TaskWorkflowState.Open)
            createInProgressView(koin)

            tapTab("Inbox")
            openSavedView(VIEW_NAME)

            // Only the in-progress task is in the section; the other task is
            // not somewhere else on the screen either.
            assertTagDisplayed(TestTags.agendaSection("In progress"))
            assertTagDisplayed(TestTags.taskItem("Started task"))
            assertTagNotExists(TestTags.taskItem("Idle task"))
        }

    @Test
    @DisplayName("AGENDA-STATE-02 a saved view follows a task after its state changes")
    fun a_saved_view_follows_a_task_after_its_state_changes() =
        runDesktopAppTest(clock = CLOCK) { koin ->
            seed(koin, "Idle task", TaskWorkflowState.Open)
            seed(koin, "Other task", TaskWorkflowState.Open)
            createInProgressView(koin)

            tapTab("Inbox")
            openSavedView(VIEW_NAME)
            assertTagNotExists(TestTags.taskItem("Idle task"))

            // Leave the results screen, move the task through its own write
            // path, then open the same card again — the view is not edited.
            awaitTag(TestTags.TOP_BAR_BACK_BUTTON).performClick()
            awaitTag(TestTags.savedAgendaCard(VIEW_NAME))
            moveToInProgress(koin, "Idle task")

            awaitTag(TestTags.savedAgendaCard(VIEW_NAME)).performClick()
            assertTagDisplayed(TestTags.agendaSection("In progress"))
            assertTagDisplayed(TestTags.taskItem("Idle task"))
            assertTagNotExists(TestTags.taskItem("Other task"))
        }

    // ── helpers ────────────────────────────────────────────────────────────

    private suspend fun seed(koin: Koin, title: String, state: TaskWorkflowState) {
        val userId = koin.get<ProfileAwareCurrentUser>().scopedUserId.value
        val task = testTask(
            id = TaskId(title),
            title = title,
            dueDate = today,
            userId = userId,
        ).copy(workflowState = state)
        koin.get<TaskRepository>().upsert(TaskDocument.of(task))
    }

    private suspend fun createInProgressView(koin: Koin) {
        koin.get<SavedAgendaViewsRepository>().upsert(
            SavedAgendaViewFactory.create(
                userId = UserId.anonymous,
                name = VIEW_NAME,
                sectionsJson = AgendaPresets.byInProgress().toSectionsJson(),
                now = FIXED_NOW,
            ),
        )
    }

    private suspend fun moveToInProgress(koin: Koin, title: String) {
        val repository = koin.get<TaskRepository>()
        val task = withTimeout(TIMEOUT_MS) {
            repository.observeAll().first { tasks -> tasks.any { it.title == title } }
                .first { it.title == title }
        }
        repository.upsert(TaskDocument.of(task.copy(workflowState = TaskWorkflowState.InProgress)))
    }

    private fun androidx.compose.ui.test.DesktopComposeUiTest.openSavedView(name: String) {
        awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        awaitTag(TestTags.savedAgendaCard(name)).performClick()
    }

    private companion object {
        const val VIEW_NAME = "In progress"
        const val TIMEOUT_MS = 10_000L

        /** Mid-month, so no assertion in this file straddles a boundary. */
        val FIXED_NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
        val CLOCK: FakeClock = FakeClock(FIXED_NOW)
        val today: LocalDate = LocalDate(2026, 9, 16)
    }
}
