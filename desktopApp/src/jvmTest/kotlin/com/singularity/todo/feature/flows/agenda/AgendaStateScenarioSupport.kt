package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.test.DesktopComposeUiTest
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
import com.singularity.todo.test.helpers.awaitTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import org.koin.core.Koin
import kotlin.time.Instant

/**
 * Shared fixtures for the two `AGENDA-STATE` scenarios.
 *
 * The traceability rule is one scenario per test file: two display-name
 * annotations carrying two scenario ids in one file are reported as two scenarios claimed
 * by one file. The scenarios therefore live in
 * `AgendaStateFilterScenarioTest` and `AgendaStateFollowScenarioTest`, and
 * everything they both need — the seeded view, the clock, the helpers — lives
 * here, where no scenario id is claimed. It is an object rather than top-level
 * members because the traceability scanner expects a Kotlin file to declare a
 * type named after the file.
 *
 * Mid-month on purpose, so no assertion in either scenario straddles a
 * boundary.
 */
internal object AgendaStateScenarioSupport {

    const val VIEW_NAME = "In progress"
    const val TIMEOUT_MS = 10_000L
    val FIXED_NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
    val CLOCK: FakeClock = FakeClock(FIXED_NOW)
    val today: LocalDate = LocalDate(2026, 9, 16)

    /** Seeds one task in [state], dated [today]. */
    suspend fun seed(koin: Koin, title: String, state: TaskWorkflowState) {
        val userId = koin.get<ProfileAwareCurrentUser>().scopedUserId.value
        val task = testTask(
            id = TaskId(title),
            title = title,
            dueDate = today,
            userId = userId,
        ).copy(workflowState = state)
        koin.get<TaskRepository>().upsert(TaskDocument.of(task))
    }

    /** Stores the saved view whose only section is grouped by workflow state. */
    suspend fun createInProgressView(koin: Koin) {
        koin.get<SavedAgendaViewsRepository>().upsert(
            SavedAgendaViewFactory.create(
                userId = UserId.anonymous,
                name = VIEW_NAME,
                sectionsJson = AgendaPresets.byInProgress().toSectionsJson(),
                now = FIXED_NOW,
            ),
        )
    }

    /** Moves the task named [title] into "In progress" through the repository. */
    suspend fun moveToInProgress(koin: Koin, title: String) {
        val repository = koin.get<TaskRepository>()
        val task = withTimeout(TIMEOUT_MS) {
            repository.observeAll().first { tasks -> tasks.any { it.title == title } }
                .first { it.title == title }
        }
        repository.upsert(TaskDocument.of(task.copy(workflowState = TaskWorkflowState.InProgress)))
    }

    /** Opens the saved view [name] through the UI, the way a user does. */
    fun openSavedView(ui: DesktopComposeUiTest, name: String) {
        ui.awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        ui.awaitTag(TestTags.savedAgendaCard(name)).performClick()
    }
}
