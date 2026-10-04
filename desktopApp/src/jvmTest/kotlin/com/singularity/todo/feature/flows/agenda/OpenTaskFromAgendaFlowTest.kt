package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.assertTextNotExists
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tasks
import com.singularity.todo.test.helpers.tapTab
import org.junit.Test

/**
 * Desktop Compose UI test verifying that clicking a task in the agenda opens
 * the task detail editor with the correct data loaded.
 *
 * This is the desktop mirror of the Maestro flow that would test
 * `Maestro/flows/agenda/01-open-task-from-agenda.yaml`.
 *
 * ## What this tests (regression guard)
 *
 * Before the Nav3 backStack.top fix in [JvmNavEntries], clicking a task in
 * the agenda would render [TaskCreateScreen] instead of [TaskDetailScreen]
 * because [NavDisplay] dispatches based on `backStack.top`, not the `start`
 * parameter passed to [TasksNavGraph]. The `tasksStack` was seeded with
 * `Create(null)`, so `backStack.top` was always `Create(null)` even when
 * navigating to a specific task's detail.
 *
 * - [AgendaNavigator.openTask] calls `onExitGraph(TasksGraph(Detail(taskId)))`
 * - `JvmNavEntries` now adds `Detail` to `tasksStack` before rendering, so
 *   `NavDisplay` dispatches to `TaskDetailScreen` immediately
 * - [TaskDetailCoordinator] loads the task and renders `TaskEditorContent`
 *   with the correct due date, priority, and title
 *
 * ## Coverage
 *
 * - `agenda → task detail` navigation on JVM Desktop
 * - `TaskDetailScreen` receives the correct `taskId` from the route
 * - `TaskDetailCoordinator` initializes from the correct task entity
 * - No stale Create screen is shown
 */
@OptIn(ExperimentalTestApi::class)
class OpenTaskFromAgendaFlowTest {

    @Test
    fun clicking_task_in_today_section_opens_task_detail_editor() = runDesktopAppTest(checkA11y = true) { koin ->
        val today = todayInSystemZone()
        tasks(koin).given(due = today, title = "Existing task")

        tapTab("Today")
        awaitTag(TestTags.taskItem("Existing task")).assertIsDisplayed()

        // Click the task row → should open TaskDetailScreen (not TaskCreateScreen)
        awaitTag(TestTags.taskItem("Existing task")).performClick()

        // The editor must show the task title, not be empty
        awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)
        assertTextDisplayed("Existing task")
    }

    @Test
    fun task_editor_shows_correct_due_date_from_agenda() = runDesktopAppTest(checkA11y = true) { koin ->
        val today = todayInSystemZone()
        tasks(koin).given(due = today, title = "Dated task")

        tapTab("Today")
        awaitTag(TestTags.taskItem("Dated task")).performClick()

        awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)
        // Due date row shows ISO date, not the placeholder
        assertTagDisplayed(TestTags.TASK_EDITOR_DUE_ROW)
        assertTextDisplayed(today.toString())
        assertTextNotExists("Добавить дату")
    }

    @Test
    fun task_editor_shows_correct_priority_from_agenda() = runDesktopAppTest(checkA11y = true) { koin ->
        val today = todayInSystemZone()
        tasks(koin).given(due = today, title = "High priority task", priority = com.singularity.todo.feature.tasks.domain.model.TaskPriority.High)

        tapTab("Today")
        awaitTag(TestTags.taskItem("High priority task")).performClick()

        awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)
        // Priority row shows the label for High priority
        assertTagDisplayed(TestTags.TASK_EDITOR_PRIORITY_ROW)
        assertTextDisplayed("High priority")
    }
}
