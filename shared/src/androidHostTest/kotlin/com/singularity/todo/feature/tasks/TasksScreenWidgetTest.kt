package com.singularity.todo.feature.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeCurrentUser
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeReminderRepository
import com.singularity.todo.test.fakes.FakeSettingsRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Widget tests for [TasksScreen] + [TasksViewModel].
 *
 * Creates ViewModel directly with all-fake dependencies — no Koin, no real DB, no network.
 */
@RunWith(AndroidJUnit4::class)
class TasksScreenWidgetTest {

    private val fakeAuthRepo = FakeAuthRepository(Session.Anonymous(UserId.anonymous))
    private val fakeCurrentUser = FakeCurrentUser(fakeAuthRepo)
    private val fakeTaskRepo = FakeTaskRepository()
    private val fakeChecklistRepo = FakeChecklistRepository()
    private val fakeReminderRepo = FakeReminderRepository()
    private val fakeSettingsRepo = FakeSettingsRepository()

    @get:Rule
    val composeRule = createComposeRule()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `tasks screen renders empty state without crashing`() {
        val viewModel = TasksViewModel(
            taskRepo = fakeTaskRepo,
            createTask = CreateTaskUseCase(fakeTaskRepo, Clock),
            updateTask = UpdateTaskUseCase(fakeTaskRepo, Clock),
            currentUser = fakeCurrentUser,
            refineTask = null,
            generateDescription = null,
            generateChecklist = null,
            decomposeTask = null,
            pickTime = null,
            scopeOverride = null,
        )
        composeRule.setContent {
            TasksScreen(
                entry = TasksScreenEntry.FromToday,
                onNavigateToTask = {},
                onNavigateToCreateTask = {},
                viewModel = viewModel
            )
        }
        composeRule.waitForIdle()
        // Default filter is Today, empty state shows filter name
        composeRule.onNodeWithText("No tasks for Today", substring = true)
            .assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `tasks screen shows empty state text for today filter`() {
        val viewModel = TasksViewModel(
            taskRepo = fakeTaskRepo,
            createTask = CreateTaskUseCase(fakeTaskRepo, Clock),
            updateTask = UpdateTaskUseCase(fakeTaskRepo, Clock),
            currentUser = fakeCurrentUser,
            refineTask = null,
            generateDescription = null,
            generateChecklist = null,
            decomposeTask = null,
            pickTime = null,
            scopeOverride = null,
        )
        composeRule.setContent {
            TasksScreen(
                entry = TasksScreenEntry.FromToday,
                onNavigateToTask = {},
                onNavigateToCreateTask = {},
                viewModel = viewModel
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("No tasks for Today", substring = true)
            .assertIsDisplayed()
    }
}
