package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.assertTextNotExists
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.clearTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.scrollToTag
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.time.Instant

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
@Tag("slow")
@Tag("slow:compose")
class OpenTaskFromAgendaFlowTest {
    @Test
    fun clicking_task_in_today_section_opens_task_detail_editor() =
        runDesktopAppTest(
            clock = CLOCK,
            checkA11y = true,
        ) { koin ->
            tasks(koin).given(due = today, title = "Existing task")

            tapTab("Today")
            assertTagDisplayed(TestTags.taskItem("Existing task"))

            // Click the task row → should open TaskDetailScreen (not TaskCreateScreen)
            awaitTag(TestTags.taskItem("Existing task")).performClick()

            // The editor must show the task title, not be empty
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)
            assertTextDisplayed("Existing task")
        }

    /**
     * The detail editor edits the title in place, so it needs the same "a task
     * needs a name" message the create editor draws — from the same rule, so the
     * two editors cannot drift into saying different things. This pins that the
     * detail screen actually hands the message to the field.
     */
    @Test
    fun clearing_the_title_in_the_detail_editor_shows_the_validation_message() =
        runDesktopAppTest(
            clock = CLOCK,
            checkA11y = true,
        ) { koin ->
            tasks(koin).given(due = today, title = "Existing task")

            tapTab("Today")
            awaitTag(TestTags.taskItem("Existing task")).performClick()
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

            clearTag(TestTags.TASK_EDITOR_TITLE_INPUT)

            // The draft is seeded from the field a beat after the keystroke, so
            // the message arrives asynchronously.
            awaitTag(TestTags.TASK_EDITOR_TITLE_ERROR)
            assertTagTextEquals(TestTags.TASK_EDITOR_TITLE_ERROR, "Title is required")
        }

    @Test
    fun task_editor_shows_correct_due_date_from_agenda() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
            tasks(koin).given(due = today, title = "Dated task")

            tapTab("Today")
            awaitTag(TestTags.taskItem("Dated task")).performClick()

            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)
            // The due date row shows the date in the screen's locale, not the ISO date
            // it used to print (#1700). "Displayed" is a claim about the screen, not the
            // tree, so the row is scrolled into view before the claim is made.
            scrollToTag(TestTags.TASK_EDITOR_DUE_ROW)
            assertTagDisplayed(TestTags.TASK_EDITOR_DUE_ROW)
            assertTextDisplayed("Wed, 16 Sep 2026")
            assertTextNotExists("Add date")
        }

    @Test
    fun task_editor_shows_correct_priority_from_agenda() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
            tasks(koin).given(
                due = today,
                title = "High priority task",
                priority = TaskPriority.High,
            )

            tapTab("Today")
            awaitTag(TestTags.taskItem("High priority task")).performClick()

            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)
            // Priority row shows the label for High priority.
            // The attribute rows sit below the fold on a 1024x768 window, so scroll
            // before asserting display — the same reason the due-date test above does.
            scrollToTag(TestTags.TASK_EDITOR_PRIORITY_ROW)
            assertTagDisplayed(TestTags.TASK_EDITOR_PRIORITY_ROW)
            assertTextDisplayed("High priority")
        }

    private companion object {
        /** Mid-month, so no assertion in this file straddles a boundary. */
        val FIXED_NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
        val CLOCK: FakeClock = FakeClock(FIXED_NOW)
        val today: LocalDate = LocalDate(2026, 9, 16)
    }
}
